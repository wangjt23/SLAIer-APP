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
│  domain/*    ScheduleRepository / CheckInRepository / ClassOccurrence / …    │
│              纯 Kotlin，无 Android 依赖（除 java.time）                       │
└───────────────────────────────┬──────────────────────────────────────────────┘
                                │ 实现
┌───────────────────────────────▼──────────────────────────────────────────────┐
│  data/*      SIS Adapter（正方） / STU Adapter（JeeSite） / Repository 实现    │
└───────┬──────────────────────────────────┬───────────────────────────────────┘
        │                                  │
┌───────▼──────────┐            ┌──────────▼──────────┐        ┌────────────────┐
│ core/network     │            │ core/web            │        │ core/database  │
│ OkHttp + Cookie  │            │ WebView 容器 + 提取  │        │ Room           │
│ 拦截器            │            │ （无 JS Bridge）     │        │                │
└───────┬──────────┘            └──────────┬──────────┘        └────────┬───────┘
        │                                  │                            │
┌───────▼──────────────────────────────────▼────────────────────────────▼───────┐
│  core/session   WebCookieBridge · SessionManager（双状态机）· SessionStore     │
└───────────────────────────────────────────────────────────────────────────────┘
```

依赖方向严格单向：`feature → domain → data → core`。`core` 不知道 `data` 的存在，
所以 `SessionManager` 通过 `interface SessionProbe` + Hilt `@IntoSet` 反向收集两个系统的探测器。

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
   └─ 响应后：response.headers("Set-Cookie")
               → bridge.saveSetCookie(url, ...)  → 写回 CookieManager + flush()
```

为什么按 URL 取而不是维护自己的 CookieJar：

- SIS 是 `JSESSIONID`（`Path=/yjsxt`），STU 是 `jeesite.session.id`（`Path=/`）；
- 一个系统加个 path-scoped cookie，全局 Jar 立刻出错；
- 把 `*.slai.edu.cn` 的 Cookie 全量发给任一子域，等于把 A 系统会话泄露给 B 系统。

`CookieManager` 必须在主线程访问，拦截器跑在 IO 线程，所以桥接处用 `Handler` 显式跳线程并带 2 秒超时。

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

## 7. 打卡三态

```kotlin
sealed interface CheckInStatus {
    data class Confirmed(...)     // 已确认
    data class NotConfirmed(...)  // 未确认
    data class Unknown(...)       // 当前无法确认   ← 接口失败落在这里
    data class NotQueried(...)    // 今天还没查过
}
```

`Unknown` 会被**持久化**，所以：

- 查询失败不会退回到昨天的"已确认"；
- 也不会显示成"未确认"（否则用户可能重复打卡或误以为漏打）；
- 卡片永远附带"打开学校打卡页面"。

判定顺序：JSON 结构化字段 → 文本标记 → 仍不明确则 `UNKNOWN`。
"已打卡 / 未打卡"同时出现的图例文本会被判为 `UNKNOWN`（有测试覆盖）。

代码：`data/stu/StuCheckInParser.kt`、`data/checkin/CheckInRepositoryImpl.kt`

---

## 8. 提醒与后台

| 需求 | 工具 | 原因 |
|---|---|---|
| 上课提醒要准点 | `AlarmManager` | WorkManager 最小 15 分钟且不精确 |
| 缓存尽量新鲜 | `WorkManager` 周期 6 小时 | 不追求某一分钟，省电 |
| 重启后恢复提醒 | `BootReceiver` → `ReminderRescheduleWorker` | Alarm 不跨重启 |
| 精确提醒 | `setExactAndAllowWhileIdle` + 运行时授权 | Android 12+ 需要用户授权，拒绝则自动降级为 `setAndAllowWhileIdle` |

已排的提醒 id 集合持久化在 DataStore，课表更新后**先取消不再存在的提醒**再排新的。

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

checkin_status   (accountHash, date) PK
  state (CONFIRMED|NOT_CONFIRMED|UNKNOWN) / detail / queriedAt / source
```

日期用 `epochDay`、时间用 `secondOfDay` 存：既可按大小排序查询，又不受时区/区域设置影响。

---

## 10. 与计划书的偏差

| 计划书 | 实现 | 原因 |
|---|---|---|
| 多模块 Gradle（`app/` `core/` `domain/` `data/` …） | 单模块 + 同构包结构 | MVP 阶段模块边界已由包保证；拆模块是机械操作，不增加当前可维护性 |
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

**校验规则与第一版一致**：找不到数组 / 行全是垃圾 → `SchemaChanged`；
只有"识别到数组且它确实是空的"才算"真的没有课"。

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
