# 架构说明

> 对应实现：`app/src/main/java/com/slai/campus/`
> 对应计划书：`docs/implementation-plan.md` §2 / §3 / §24

---

## 1. 分层

```text
┌──────────────────────── UI（feature/*, navigation/*） ────────────────────────┐
│  只读 StateFlow，不做任何网络/周次/日期计算                                    │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                │ 依赖接口
┌───────────────────────────────▼──────────────────────────────────────────────┐
│  domain/*    ScheduleRepository / AttendanceRepository / ClassOccurrence / … │
│              纯 Kotlin，无 Android 依赖（除 java.time）                       │
└───────────────────────────────▲──────────────────────────────────────────────┘
                                │ 实现接口，依赖 domain
┌───────────────────────────────┴──────────────────────────────────────────────┐
│  data/*      SIS Adapter（正方） / STU Adapter（JeeSite） / Repository 实现    │
└───────┬──────────────────────────────────┬───────────────────────────────────┘
        │                                  │
┌───────▼──────────┐            ┌──────────▼──────────┐        ┌────────────────┐
│ core/network     │            │ core/web            │        │ core/database  │
│ OkHttp 每跳桥接   │            │ WebView 容器 + 提取  │        │ Room           │
│ 拦截器            │            │ （无 JS Bridge）     │        │                │
└───────┬──────────┘            └──────────┬──────────┘        └────────┬───────┘
        │                                  │                            │
┌───────▼──────────────────────────────────▼────────────────────────────▼───────┐
│  core/session   WebCookieBridge · SessionManager（双状态机）· SessionStore     │
└───────────────────────────────────────────────────────────────────────────────┘
```

业务页面依赖 `domain` 中的仓库接口，`data` 实现这些接口，即 `feature → domain ← data`；
`data` 再使用 `core` 的网络、会话、缓存与 WebView 能力，`di/AppModule.kt` 负责绑定实现。
当前是单个 Gradle 模块，包结构表达职责，编译器尚未强制隔离每层。

诊断、接口配置页面和登录编排需要直接访问 `data`/`core`，是现有明确的例外。
`SessionManager` 不直接依赖学校适配器，通过 `SessionProbe` + Hilt `@IntoSet` 收集两个系统的探测器。
考勤模型、仓库接口、刷新结果分别位于 `AttendanceRecord.kt`、`AttendanceRepository.kt`、`AttendanceRefresh.kt`。

---

## 2. 会话状态机（两个，不是一个）

```text
                  ┌─────────────┐
                  │   UNKNOWN   │  ← 装机后 / 未探测
                  └──────┬──────┘
                         │ probe()
        ┌────────────────┼────────────────┐
        ▼                ▼                ▼
 AUTHENTICATED      EXPIRED           ERROR
        │                │                │
    refresh()        NEEDS_LOGIN      （离线/服务端故障，
        │                │              不代表掉线）
   ┌────┴────┐      登录 WebView
   ▼         ▼           │
成功      SessionExpired  ▼
   │         │      AUTHENTICATING
   ▼         └──────────┬──→ AUTHENTICATED → 立即 refresh()
 AUTHENTICATED          │
                        └──→ EXPIRED（用户取消/密码错误）
```

关键点：

- `sis` 和 `stu` 各自独立。`sis=AUTHENTICATED, stu=NEEDS_LOGIN` 是合法状态。
- `ERROR`（网络不可用、服务器 5xx）**不等于** `EXPIRED`，不会弹登录。
- 后台 Worker 遇到非 `AUTHENTICATED/UNKNOWN` 直接停止，**绝不从后台弹登录**。
- 状态持久化在 DataStore，冷启动后仍然记得"哪个系统需要重新登录"。

代码：`core/session/SessionState.kt`、`core/session/SessionManager.kt`

---

## 3. Cookie 桥

```text
用户正常登录（WebView）
   │
   │  CookieManager（Android 系统维护，按 host/path 自动隔离）
   ▼
WebViewCookieInterceptor.intercept()
   ├─ 请求前：bridge.cookieHeader(request.url)   ← 按具体 URL 取，不汇总
   │           → 注入 Cookie 头
   └─ 响应后：普通请求的 response.headers("Set-Cookie")
               → bridge.saveSetCookie(url, ...)  → 写回 CookieManager + flush()
```

为什么按 URL 取而不是维护自己的 CookieJar：

- SIS 是 `JSESSIONID`（`Path=/yjsxt`），STU 是 `jeesite.session.id`（`Path=/`）；
- 一个系统加个 path-scoped cookie，全局 Jar 立刻出错；
- 把 `*.slai.edu.cn` 的 Cookie 全量发给任一子域，等于把 A 系统会话泄露给 B 系统。

桥接处统一通过 `WebCookieBridge` 的阻塞访问方法跳到主线程，并带 2 秒超时。
标记为 `ReadOnlySessionCheck` 的会话检查只借用 Cookie，不写回响应 Cookie。
普通响应只有在请求时的浏览器版本、目标主机版本及 Cookie 快照仍有效时才写回；SIS/STU 正常续期互不作废。
浏览器加载期间保留读取能力、暂停原生写回，开始/结束都会使旧请求失效。所有学校客户端逐次网络跳转桥接，
Referer 不携带查询参数、片段或跨站原始页面地址。可见 WebView 的保护范围也在弃置组合时释放。

代码：`core/session/WebCookieBridge.kt`、`core/network/WebViewCookieInterceptor.kt`

---

## 4. 课表刷新闭环

```text
refresh(reason)
  │
  ├─ 无网络 ────────────────────────────────→ Offline(cached=true)   [缓存不动]
  │
  ├─ discoverSemester()  读课表页 select#xnm / select#xqm / select#xq
  │      └─ 落到登录页 ─────────────────────→ SessionExpired         [缓存不动]
  │
  ├─ resolveSemester()   锚点优先级：用户确认 > 页面观测周次反推 > 学期估算
  │
  ├─ ① 原生接口 POST /yjsxt/xskbcx/xskbcx_cxXsKb.html
  │      Success ──→ 校验通过 ──→ Room 事务 replaceAll ──→ 重排提醒 ──→ Success
  │      SessionExpired ──────→ SessionState.EXPIRED，返回
  │      SchemaChanged/5xx ───→ 降级到 ②
  │
  ├─ ② WebView 提取：加载课表页 → 注入 hook 抓页面自己的 XHR
  │      Success(XHR) ────────→ Room 事务 replaceAll
  │      Success(DOM) ────────→ Room 事务 replaceDates（只有当前周）
  │      SessionExpired ──────→ SessionState.EXPIRED
  │      其它 ────────────────→ 降级到 ③
  │
  └─ ③ 分类失败，缓存不动，UI 显示对应文案 + 一键打开原网页
```

**原子替换规则**（计划书 §12.1 明确禁止的写法）：

```kotlin
// ✗ 错误：先删后取，取失败用户课表就空了
dao.deleteAll(); val data = api.fetch(); dao.insert(data)

// ✓ 正确：先取 → 完整解析 → 完整校验 → 事务内替换
val parsed = parser.parse(api.fetch()) ?: return SchemaChanged(...)
dao.replaceAll(hash, parsed.occurrences)   // @Transaction
```

代码：`data/schedule/ScheduleRepositoryImpl.kt`、`core/database/Daos.kt#replaceAll`

---

## 5. WebView 提取为什么可靠

传统抓包式适配需要猜 `xnm`/`xqm`/`kzlx` 等参数，学校一改就崩。本实现改成：

```text
加载学校课表页
   ↓ 注入（onPageStarted + onPageFinished 各一次）
XMLHttpRequest.prototype.open/send 和 window.fetch 被包装
   ↓
页面自己发课表请求（参数是它自己填的，永远正确）
   ↓
响应体被存进 window.__campusCapture[]
   ↓
原生 evaluateJavascript("JSON.stringify(window.__campusCapture)")
   ↓
交给同一个 SisScheduleParser 解析
```

**没有注册任何 JavaScript Bridge**（`addJavascriptInterface` 零调用），
所以被注入的页面**没有**任何回调进 App 的能力——它只能往自己的全局变量里写字符串。

第二条兜底路径是 DOM 抓取：把页面上最大的表格解析成二维网格，再启发式识别"课程 / 教师 / 教室 / 周次"。
这条路只在页面是服务端渲染（无 JSON）时才会走到，结果标记为 `SIS_WEBVIEW_DOM`，UI 会显示"可能不完整"。

代码：`core/web/WebViewExtractor.kt`、`data/sis/SisWebViewDataSource.kt`

---

## 6. 周次 → 日期

正方只给"星期几 + 第 1-16 周 + 节次"，UI 需要的是具体日期。

```text
WeekRange(1..16, ODD) + 星期 3 + 节次 5-6
        │
        ▼  SemesterCalendarResolver 定出第一周星期一
        ▼  SisScheduleNormalizer.normalize()
ClassOccurrence(date=2026-09-09, start=13:50, end=15:30, weekIndex=1, …)
```

锚点证据优先级：

1. **用户确认**（设置里选过日期）——最高；
2. **页面观测**：课表页 `select#xq` 的选中值就是当前周次 N，反推 `monday(today) - (N-1) 周`；
3. **学期估算**：`xqm=3` → 当年 9 月第一个周一；`xqm=12` → 次年 2 月；`xqm=16` → 次年 7 月。

锚点缺失时**不猜日期**：`normalize()` 返回空 + 警告，刷新结果变成 `SchemaChanged`，UI 提示去设置里确认。

代码：`data/sis/SisScheduleNormalizer.kt`、`domain/schedule/Semester.kt`

---

## 7. 考勤记录与实时在馆时长

考勤使用学校月度台账与独立闸机流水，已不存在旧 `CheckInRepository` / `checkin_status` 存储。

- `StuAttendanceParser` 解析月度每天记录、学校判定和统计字段。
- `StuPunchParser` 解析每次进出，`PunchPairing` 在完整时间线上配对后归属日期，保留跨午夜上下文。
- 历史非请假日优先学校累计时长；请假日只累计实际进出；当天可根据有效流水实时估算。
- 页面只观察 Room 缓存；刷新通过 `AttendanceRepository` 请求学校接口，失败保留缓存并返回失败结果。
- `refreshWithPunches` 合并月度台账和流水的刷新结果，避免汇总成功掩盖流水失败。

代码：`domain/attendance/*`、`data/attendance/AttendanceRepositoryImpl.kt`、`data/stu/StuAttendanceParser.kt`。
`StuCheckInParser` 仍供诊断及登录页识别使用，不能仅凭旧名字删除。

---

## 8. 提醒与后台

| 需求 | 工具 | 原因 |
|---|---|---|
| 上课提醒要准点 | `AlarmManager` | WorkManager 最小 15 分钟且不精确 |
| 课表同步 | 用户手动刷新或登录后刷新 | 日常读取使用缓存 |
| 延续未来课程提醒 | `ReminderRescheduleWorker` | 重新安排已有缓存，不请求课表 |
| 应用更新检查 | `UpdateCheckWorker` | 每日检查，使用静态版本清单 |
| 重启后恢复提醒 | `BootReceiver` → `ReminderRescheduleWorker` | Alarm 不跨重启 |
| 精确提醒 | `setExactAndAllowWhileIdle` + 运行时授权 | Android 12+ 需要用户授权，拒绝则自动降级为 `setAndAllowWhileIdle` |

已排的提醒 id 集合持久化在 DataStore，课表更新后**先取消不再存在的提醒**再排新的。
Repository 在成功持久化课表后统一重排提醒。`ScheduleSyncWorker` 只保留旧版本已持久任务的兼容入口，
启动时取消旧任务，不再暴露新的周期或立即同步入队方法。

代码：`reminder/ReminderScheduler.kt`、`worker/*`

---

## 9. 数据模型

```text
course_occurrence
  id            TEXT PK   ← accountHash|date|start|period|courseCode|teachingClass
  accountHash   TEXT      ← 缓存分区键（SHA-256，不可逆）
  courseName    TEXT
  teacher       TEXT?
  location      TEXT?
  date          DATE      ← epochDay
  startTime     TIME      ← secondOfDay
  endTime       TIME
  source        TEXT      ← SIS_NATIVE / SIS_WEBVIEW_XHR / SIS_WEBVIEW_DOM / MANUAL
  weekIndex     INT?
  periodStart   INT?
  periodEnd     INT?
  courseCode    TEXT?
  teachingClass TEXT?
  campus        TEXT?
  updatedAt     INT

sync_meta        (accountHash, system) PK
  lastSuccessAt / lastAttemptAt / parserVersion / lastError / integrationMode

attendance_record   (accountHash, month, date) PK
  month / 日类型 / 进出次数 / 学校时长与判定 / 请假与申诉字段

attendance_meta     (accountHash, month) PK
  lastSuccessAt / lastAttemptAt / lastError / 学校 stats

attendance_punch    id PK
  accountHash / time / date / direction / channel / place / result
```

日期用 `epochDay`、时间用 `secondOfDay` 存：既可按大小排序查询，又不受时区/区域设置影响。

---

## 10. 与计划书的偏差

| 计划书 | 实现 | 原因 |
|---|---|---|
| 多模块 Gradle（`app/` `core/` `domain/` `data/` …） | 单模块 + 分包 | 当前规模可先以清楚职责和测试维护；包边界不是编译期约束，未来按实际依赖再拆模块 |
| Retrofit | 直接 OkHttp | 需要多候选 endpoint 动态尝试 + 检查 302/200 两种掉线形态，Retrofit 的静态接口反而碍事 |
| Navigation Compose / Navigation 3 | 手写 4 tab + 2 overlay | 无深链、无参数传递，手写 Back 行为比图更可控 |
| `addJavascriptInterface` 提数据 | 注入脚本写全局变量 + `evaluateJavascript` 读回 | 计划书要求"不向任意页面暴露 JS Bridge"，这样连 Bridge 都没有 |
| Moshi | kotlinx.serialization | 与 Kotlin 2.2 编译器插件同源，无需额外适配器 |

---

## 11. API Provider 层（第二轮新增）

### 11.1 为什么需要它

第一版把端点路径写死在 Kotlin 里，结果是错的——而且"错了"这件事无法从外部发现：

```text
不存在的路径   /yjsxt/zzz-nope.html        → 302 → 登录页
猜测的路径     /yjsxt/xskbcx/..._cxXsKb    → 302 → 登录页
静态资源探针   /yjsxt/js/.../nope.js       → 404      ← 只有静态资源能区分存在性
```

所以端点必须是**数据**，不是代码。改一个 URL 或字段名不应该需要重新编译 APK。

### 11.2 模型

```kotlin
ApiProvider(
    id, name, system("sis"), purpose("timetable"),
    method, url, headers, body, contentType,
    rowsPath,       // "kbList" / "data.list" / "" (根数组)
    fieldMap,       // 逻辑字段 -> 响应字段，如 courseName -> "kcmc"
    enabled, learnedAt, note
)
```

执行链：

```text
ApiProvider
   ↓ ProviderExecutor（用带 Cookie 桥的 OkHttp，不跟随重定向）
HTTP 响应（status / contentType / body / 耗时）
   ↓ GenericTimetableParser
   ① resolveRows(root, rowsPath)      找课程数组
   ② fieldMap 映射成 SisKbItem
   ↓ SisScheduleNormalizer（复用第一版，已有单测覆盖单双周/多段周次/节次）
List<ClassOccurrence>
```

解析器区分找不到数组、垃圾行和可信空数组；Repository 当前仍拒绝所有 0 课程结果。
这两层对可信空课表的处理不一致，是文末列出的待修复问题。

### 11.3 抓包学习

```text
WebView 加载学校页面
   ↓ 注入 hook（包装 XMLHttpRequest.open/send 和 fetch）
页面自己发请求 → hook 记录 {method, url, reqBody, status, body, pageUrl}
   ↓ 原生 evaluateJavascript 读回（无 JS Bridge）
ProviderLearner.analyze()
   ├─ 每个 JSON 响应里找数组
   ├─ 要求 ≥2 个强特征字段（kcmc / zcd / xqj / jcs / cdmc / kch / jxbmc）
   ├─ 按命中数量 + 行数打分
   └─ 用实际出现的键反查别名表，推导 fieldMap
   ↓
生成 ApiProvider（并把请求体里的 xnm/xqm 替换成 {{xnm}}/{{xqm}}）
```

关键设计：

- **强特征阈值 2**：只命中一个字段不足以判定"这是课表"，宁可报"没找到"也不误采纳；
- **学期模板化**：抓包时的 `xnm=2026&xqm=3` 会变成 `xnm={{xnm}}&xqm={{xqm}}`，
  下学期继续可用；
- **抓包只记录，不注入 Bridge**：页面无法回调进 App。

### 11.4 刷新管线（更新后）

```text
refresh()
  ├─ 无网络 → Offline
  ├─ 若已配置 provider 且学年/学期/锚点都已确认 → 跳过页面发现（1 个请求搞定）
  │  否则 → discoverSemester()（读 select#xnm / #xqm / #xq）
  ├─ ① 遍历 enabled providers → 执行 → 解析 → 成功则落库
  ├─ ② 内置正方候选（NATIVE_API）
  ├─ ③ WebView 提取（WEBVIEW_EXTRACT）
  └─ ④ 分类失败 + 网页兜底入口
  全程共享 75s deadline，阶段通过 RefreshPhase 暴露给 UI
```

### 11.5 新增/修改的文件

```text
domain/provider/ApiProvider.kt          provider 模型 + 校验 + 字段别名表
data/provider/ProviderStore.kt          DataStore 持久化（独立文件，JSON 数组）
data/provider/ProviderExecutor.kt       按定义发请求
data/provider/GenericTimetableParser.kt 响应 → 领域模型
data/provider/ProviderLearner.kt        抓包 → provider
data/provider/CaptureBus.kt             抓包结果从 overlay 传给 provider 页
core/web/CaptureScript.kt               共享注入脚本 + CaptureRecord
core/web/SchoolWebScreen.kt             新增 captureEnabled 模式 + 计数器轮询
feature/provider/ProviderScreen.kt      接口配置 UI
feature/provider/ProviderViewModel.kt   增删改查 / 测试 / 学习 / 日志
```

## 12. 2026-10-12 架构清理与待修复问题

本轮统一 Cookie 主线程桥和学期锚点选择，删除没有运行消费者的 DAO/仓库方法、同步状态、旧任务入队函数、
无用注入和过时登录页状态，并按记录模型、仓库接口、刷新结果拆分考勤领域文件。
数据库实体、schema 版本、学校接口和真实缓存更新规则未改动。旧 Worker 类仍供持久任务兼容。

当前单模块分包适合项目规模。下一步最有价值的是拆分 `ScheduleRepositoryImpl.doRefresh` 中的学期发现、
数据来源降级和缓存持久化职责，以及将 `MainViewModel` 的自动登录编排交给生命周期由调用方管理的协调类。
拆分时需要覆盖取消、并发和账号切换的测试，不能只移动代码文件。

以下是初次审查核查出的行为问题，后续修复对应关系见 §13。P1 表示优先处理的账号隔离/数据可见性问题；
P2 表示依赖取消、换学期、数据量或并发时序触发的问题。

1. **P1：只切换学生系统账号时会混用缓存。**
   `SessionManager.signOut` 不重置账号键；`MainViewModel.onWebPageFinished` 的学生系统分支只调用
   `requireAccountHash`，STU 响应没有切换账号键的流程。已有 A 账号缓存后仅登录 B 的学生系统，
   新数据写到 A 的分区，未刷新的历史月份仍可能显示 A 的记录。需在获取可验证的学校账号身份后切换缓存分区。

2. **P1：首次发现教务学号时，刚同步的课表可能消失。**
   `ScheduleRepositoryImpl.persist` 按旧 `accountHash` 写课程和同步元数据，随后
   `onStudentIdDiscovered` 才切到学号哈希，后者不迁移 Room 行。观察者及提醒改查新分区，
   旧分区中的刚同步数据不可见。应先确定身份再生成课程 ID/落库，或事务性迁移本次数据。

3. **P2：首次并发请求可能创建两个随机缓存键。**
   `SessionManager.requireAccountHash` 的读取、随机生成、保存没有互斥。无学号提示且没有已有账号键时，
   课表与考勤的并行调用可返回不同键，其中一个分区不再可见。初始化必须原子化。

4. **P2：相邻教学月份的台账快照互相覆盖。**
   `AttendanceRecordEntity` 主键为 `accountHash,date`，但行的 `month` 是请求月份。
   教学周跨月时，同一天可出现在相邻月份响应中；后一次 `INSERT OR REPLACE` 会改写该日的月份归属，
   使前一个月份的缓存缺行。相同主键与写入方式已用内存 SQLite 复现。需独立月份快照关联或调整快照键，
   不能简单删除跨月日期，因为教学周本来就需要这些日期。

5. **P2：刷卡流水只取第一页。**
   `StuPunchDataSource.request` 固定 `page=1,limit=500`，没有依据 `count` 翻页；
   Repository 随后替换整个查询区间。超过 500 条，或无日期过滤回退查历史时，会把不完整流水当成完整结果，
   甚至将缓存历史区间替换为空。应分页读取并验证完整性，完整之前保留原缓存。

6. **P2：旧学期及锚点可能长期被沿用。**
   `ScheduleRepositoryImpl` 在已有 Provider、学期代码和确认锚点时跳过当前学期探测；
   锚点没有绑定学年学期，服务端观测周次还会被标成确认，与用户确认语义混合。
   换学期后可能继续请求旧学期或使用旧日期。应绑定锚点的学期归属，明确用户确认和服务器观测，并定期核实学期。

7. **P2：可信空课表不能替换旧课程。**
   `SisScheduleParser` 接受带课表元数据的空 `kbList`，但 `ScheduleRepositoryImpl.persist` 无条件拒绝 0 行。
   服务器确实表示无课时仍保留旧课程并报结构错误。需把可信空学期与不可信空响应区分后再决定是否替换缓存。

8. **P2：取消更新检查后状态可能卡住。**
   `UpdateRepositoryImpl.check` 写入 `Checking` 后没有取消时的 `finally` 恢复；后续检查又直接拒绝 `Checking`。
   Worker 约束变化或页面协程销毁可触发，下载流程也存在相同的状态恢复缺口。
   应串行化更新操作，传播取消异常并恢复可操作状态。

9. **P2：清除登录遗漏非根路径 Cookie。**
   `WebCookieBridge.clearHostCookies` 只从根 URL 读取 Cookie 名称，读不到 `Path=/yjsxt` 等受保护路径的名称。
   即使后面枚举路径，也无法删除未读到的名称。需按所有学校受保护路径收集名称，再删除对应 domain/path 组合。

10. **P2：普通考勤请求仍可能覆盖新登录 Cookie。**
    `ReadOnlySessionCheck` 只保护会话探测；普通月台账、流水和原生续期请求仍写回 Cookie。
    若旧会话请求在登录前发出、在新登录后返回匿名 `Set-Cookie`，可覆盖新凭证。
    这是条件性的并发路径，不代表每次学校响应都会触发；需关联请求开始时的会话版本并拒绝旧版本写回。

11. **P2：接口配置并发修改可能丢更新。**
    `ProviderStore.upsert/delete/setEnabled` 在 DataStore `edit` 外读取列表，再覆盖写回。
    两个操作读取同一旧列表时，后写入会丢掉前一次修改。需在一次 `edit` 内完成解码、修改和编码。

## 13. 逐项修复实现

| 审查项 | 实现 | 回归覆盖 |
|---|---|---|
| 1 学生账号隔离 | SIS/STU 各自维护分区；保护响应的 `userNo` 只以哈希参与账号选择；明确登录后无身份的响应使用新匿名分区 | 真实 DataStore 的账号切换、同账号重登、空身份、旧响应拒绝；学生接口合成身份测试 |
| 2 首次识别学号 | 先选定身份再生成课程缓存 ID、写课程及元数据；ID 包含完整账号键 | 初次/重复同步 ID 稳定性、账号前缀碰撞、空课表 profile 学号 |
| 3 并发初始化 | DataStore 单次 edit 初始化；账号选择与缓存写入受同一锁保护 | 40 个并行初次请求只创建一个键，登录变更等待写入完成 |
| 4 跨月快照 | 主键加入请求月份；Room 3→4 显式迁移，保留旧行及其他表 | 真实旧库升级、全部字段保留、相邻月同日共存、独立替换 |
| 5 流水分页 | 依据稳定总数读取全部页；重复、残缺或数量变化返回失败；日期回退也完整分页 | 多页、空页、重复、错行、总数变化、读取上限及日期回退 |
| 6 学期归属 | 锚点原子绑定学年学期；确认和观测分离；配置最多复用 24 小时 | 真实 DataStore 学期切换、旧配置迁移、确认/观测优先级 |
| 7 可信空课表 | 仅完整、匹配学期且经过学校解析校验的空台账允许替换 | 无元数据/其他学期/普通 provider/DOM 空页保留缓存，学校可信空清理旧课 |
| 8 更新取消 | 操作互斥且不排队；取消恢复可操作状态；独立临时包；安装回调关联 sessionId | 真实仓库与本地 HTTP 取消、流式下载重试、迟到回调；已验证包可重试安装 |
| 9 清除会话 | 目标主机所有保护路径/观察路径读取并删除；等待完成；保留父域共享及兄弟系统 Cookie | 真实 CookieManager 路径、Domain/Secure 及共享 Cookie 隔离 |
| 10 登录并发 | 每跳 Cookie 快照与版本保护；浏览器生命周期隔离；账号期望版本与认证证据阻止旧失败覆盖新状态 | 真实 HTTP 重定向、双主机续期、旧响应写回、主线程超时、弃置组合释放、同账号启动探测/刷新交错 |
| 11 配置并发 | 列表读取、修改和编码在同一次 DataStore edit 内完成 | 真实 DataStore 并发新增、修改、替换及删除 |

学生接口的账号字段已在用户登录后的页面核对：`userNo` 与页面学号一致，响应中一致。核对只输出字段名和一致性布尔值，
不保存原始账号、Cookie 或个人明细；所有自动测试使用合成账号、本地表单与本地 HTTP。

升级兼容：旧 SIS 分区保留；STU 需由新的保护响应确认身份后选回对应分区。旧匿名缓存无法证明所属账号，
不直接混入已识别的新账号，相关月份可重新刷新。数据库迁移保留原始缓存，并不凭空修复旧版已被覆盖的跨月行。

第一轮修复本地验证：385 项 JVM 单元测试、13 项 Android 集成测试全部通过，Release 构建和 APK 正式签名检查通过，
`git diff --check` 通过。Android 测试包含实际 Room 3→4 迁移、真实 CookieManager、本地 HTTP 重定向、离屏表单和更新取消重试。
Lint 报告仍为原有 `NotificationHelper.kt:108` 的 MissingPermission 错误及 61 条警告，本轮未增加错误。
真实学校隔天续登、超过 500 条真实流水的分页稳定性，以及系统 PackageInstaller 完成覆盖安装，仍需试用确认。
试用包为 `dist/slaier-1.2.3-review-fixes.apk`，沿用包内版本 1.2.3（12），没有提交、标签、推送或发布。

## 14. 修复后的独立复查

再次按账号、数据完整性、浏览器生命周期和更新流程交叉审查，发现以下遗漏并补修：

| 遗漏 | 修正后的行为 | 新增回归证据 |
|---|---|---|
| 教务退出/登录会修改学生系统的自动登录开关或暂停状态 | 保存账号与自动登录状态仅响应 STU 操作；SIS 登录及退出不修改它们 | Android 真正 Keystore 读写与两个系统交错操作 |
| 旧登录页的成功探测会关闭后来打开的页；读取旧响应体会占住探测锁 | 每次页面独立 `flowId`，确认事件及延迟关闭均核对当前页面；取消 SIS 探测同时取消网络调用并释放协程锁 | 替换页面、延迟事件、真实 SessionManager 与阻塞响应体 |
| 退出后旧 WebView 的迟到响应可能恢复 Cookie | 清除前同步停止并销毁目标系统浏览器、取消所属任务；新手动登录也先停止旧浏览器 | 真实 Chromium + 本机延迟 HTTPS 的控制组及退出组；保留另一系统浏览器 |
| 退出期间 OkHttp 重定向或连接重试可能重新取得写权限 | 整次调用保留起始会话版本，每跳仍读取对应 URL 的 Cookie；迟到响应不能借重试获得新权限 | 本机真实 HTTP 重定向和 EOF 引起的连接重试 |
| 旧失败写入与新成功交错时可能覆盖新状态；空 200 响应被视作已登录 | 写状态时在同一账号锁内核对认证证据；空白或读取失败的成功响应返回 ERROR，登录表单检测支持合法 HTML 属性引号 | 真正状态管理器的受控交错写入；空/坏响应体和登录表单 |
| 学习接口未完整校验非空学校课表的账号与学期 | 默认学校映射复用原生解析；自定义映射也统一校验 profile/行身份、错误码及学期 | profile-only、混合身份、失败响应、不同学期及自定义映射 |
| 不完整考勤周或坏日期可被当成成功并清空/截断缓存 | 所有声明的周和日期均须有效；拒绝缺周、非法行和重复日期，保留原缓存；显式合法空月和跨月周仍支持 | 不完整响应、空月、数据型周、辅助字段和会话验证 |
| 学习接口成功时漏掉学校节次时间，课程和提醒可能使用默认钟点 | 一次同步共用学校节次时间，学习接口、原生接口、重试及测试预览均传入；读取受总截止时间与任务有效性限制 | 已脱敏的学校 `sjkList=[]` fixture，学习与原生全部日期/起止一致，自定义映射首节 09:30 |

此次复查没有保存新的学校账号、Cookie 或个人记录。新增网络用例仅访问本机 HTTP/HTTPS，
HTTPS 自签名证书仅由测试 WebView 对该本机地址接受，生产 SSL 错误仍拒绝。

通知原有的 MissingPermission 报告源自跨函数权限检查和通用 `runCatching` 无法被 Lint 识别。
保留发送前权限检查，并在实际发送处明确捕获 `SecurityException`，涵盖检查后权限被撤销的情况；
没有添加全局或局部 Lint 屏蔽。

复查补修后的最终验证：427 项 JVM 单元测试（0 失败、0 错误、0 跳过）、17 项 Android 集成测试全部通过；
Release 构建通过，`git diff --check` 通过。Lint 报告为 0 错误、61 条警告，原有通知权限错误已解除。
两项延迟 HTTPS 用例实际完成 Chromium 请求：控制组确认 Set-Cookie 写入，退出组确认同步销毁后 Cookie 没有恢复，
不是仅以模拟回调或请求超时作为成功。TLS fixture 初次握手与证书重连问题已经修正后重跑通过。

已更新同名 `dist/slaier-1.2.3-review-fixes.apk` 及其 `.apk.sha256`，包内仍为 1.2.3（versionCode 12），
applicationId 为 `com.slai.campus`，签名与已发布 1.2.3 一致，可覆盖试用。正式版本 APK 与校验文件未覆盖。
修复目前只在本地，没有提交、标签、推送或 Release。真实学校隔天续登、超过 500 条真实流水，以及系统安装器
完成覆盖安装仍需实机试用；本次复查未再定位到新的明确阻塞问题，不代表已穷尽全部设备及服务器行为。
