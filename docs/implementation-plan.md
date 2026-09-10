# 校园 App（Android）完整闭环落地技术方案

> 编写日期：2026-09-09  
> 文档版本：v1.1（加入 Android 官方 `android/skills` AI 辅助开发工具链）  
> 目标：将 `stu.slai.edu.cn` 与 `sis.slai.edu.cn` 集成到一个 Android App 中，以“课表原生化 + 打卡状态原生化（可行时）+ 复杂操作网页兜底”为核心，形成可用、可维护、可降级的闭环。  
> 适用范围：个人自用 / 小范围测试优先；如果要正式面向全校分发，应额外确认学校的第三方客户端、统一认证和接口使用政策。

---

# 0. 一句话方案

采用 **Hybrid Adapter Architecture（混合适配架构）**：

- 登录：优先复用学校现有 Web 登录链路；
- 高频只读功能：课表、打卡状态尝试走原生数据适配；
- 复杂写操作：继续打开学校原网页；
- 登录失效：回到登录页重新认证，不保存密码，不自动代填密码；
- 原生接口失效：保留最后一次有效缓存，并降级到原网页；
- 后台同步：WorkManager；
- 准时课程提醒：AlarmManager；
- 所有模块互相解耦，SIS 失效不影响 STU，反之亦然。

最终用户体验：

```text
打开 App
   ↓
显示本地缓存的今日课程
   ↓
后台/前台尝试刷新
   ↓
┌─────────────成功─────────────┐
│ 更新课表 + 更新时间 + 重排提醒 │
└──────────────────────────────┘
   ↓失败
判断失败原因
   ├─ 无网络 → 继续显示缓存
   ├─ 会话过期 → 显示“需要重新登录”
   ├─ 接口结构变化 → 保留旧数据 + 提示网页查看
   └─ 服务端错误 → 保留旧数据 + 稍后手动刷新
```

这就是整个 App 的核心闭环。

---

# 1. 明确范围

## 1.1 第一版必须实现

1. 学校教务系统登录入口；
2. 学校学生系统登录入口；
3. 今日课程；
4. 本周课程；
5. 课表本地缓存；
6. 手动刷新；
7. 登录失效检测；
8. 课表接口失效后的网页兜底；
9. 学生系统打卡状态展示（仅在技术验证通过后启用）；
10. 打卡原网页入口；
11. 本地上课提醒；
12. 断网情况下仍能查看最近一次有效课表。

## 1.2 第一版明确不做

- 自动选课；
- 自动抢课；
- 自动提交打卡；
- 模拟定位；
- 自动填写或保存校园账号密码；
- 绕过 MFA、验证码或设备安全策略；
- 绕过 TLS / 证书校验；
- 高频轮询校园服务器；
- 建立收集学生账号密码的第三方服务器。

---

# 2. 最终架构

```text
┌──────────────────────────────────────────────┐
│                Android App                   │
│                                              │
│  ┌────────── UI / Compose ───────────────┐   │
│  │ 首页                                  │   │
│  │ 今日课程 / 本周课表                   │   │
│  │ 打卡状态                              │   │
│  │ 学生系统 / 教务系统网页入口           │   │
│  └───────────────────────────────────────┘   │
│                    │                         │
│  ┌──────── Domain Layer ────────────────┐    │
│  │ ScheduleRepository                   │    │
│  │ CheckInRepository                    │    │
│  │ SessionRepository                    │    │
│  │ ReminderScheduler                    │    │
│  └──────────────────────────────────────┘    │
│            │                │                │
│  ┌─────────▼───────┐  ┌────▼────────────┐   │
│  │ SIS Adapter     │  │ STU Adapter     │   │
│  │ 课表数据适配     │  │ 打卡数据适配     │   │
│  └─────────┬───────┘  └────┬────────────┘   │
│            │                │                │
│  ┌─────────▼────────────────▼────────────┐   │
│  │       Native HTTP / OkHttp           │   │
│  │ Cookie / Session Bridge              │   │
│  └─────────────────┬────────────────────┘   │
│                    │                         │
│  ┌─────────────────▼────────────────────┐    │
│  │ WebView Session / Login Container    │    │
│  │ sts.slai.edu.cn                      │    │
│  │ stu.slai.edu.cn                      │    │
│  │ sis.slai.edu.cn                      │    │
│  └──────────────────────────────────────┘    │
│                                              │
│  ┌───────────────────────────────────────┐   │
│  │ Room / Local Cache                    │   │
│  │ last successful schedule             │   │
│  │ sync metadata                         │   │
│  └───────────────────────────────────────┘   │
│                                              │
│  WorkManager                  AlarmManager   │
│  尽力后台同步                  上课提醒       │
└──────────────────────────────────────────────┘
```

---

# 3. 核心设计原则

## 3.1 STU 和 SIS 不共享一个全局登录状态

分别维护：

```kotlin
enum class SessionState {
    UNKNOWN,
    AUTHENTICATING,
    AUTHENTICATED,
    EXPIRED,
    NEEDS_LOGIN,
    ERROR
}
```

运行时状态：

```text
sisSessionState = AUTHENTICATED
stuSessionState = NEEDS_LOGIN
```

这是允许的。

不要写成：

```text
isLoggedIn = true / false
```

否则一个系统掉线会影响另一个系统。

---

## 3.2 WebView 是“认证和网页兜底层”，不是整个 App

WebView 负责：

- 打开现有登录流程；
- 完成学校自己的跳转；
- 保留学校已有网页功能；
- 在原生适配失败时兜底。

WebView 不负责：

- 存储校园密码；
- 自动代填账号密码；
- 自动模拟选课或打卡；
- 向任意页面暴露 JavaScript Bridge。

---

## 3.3 原生数据库中保存“业务数据”，不保存密码

Room 保存：

- 课表；
- 课程日期；
- 教师；
- 教室；
- 同步时间；
- 数据版本；
- 账号标识的不可逆 hash（用于隔离缓存）。

不要在 Room 保存：

- 明文密码；
- AD FS 密码；
- 完整 Cookie dump；
- Access Token 调试日志；
- HAR 文件。

---

# 4. Step 0：建立项目

## 4.1 工程技术栈

建议：

| 模块 | 技术 |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| Architecture | MVVM + Repository |
| DI | Hilt |
| HTTP | OkHttp + Retrofit |
| JSON | Kotlin Serialization 或 Moshi |
| Database | Room |
| Preferences | DataStore |
| Background Sync | WorkManager |
| Reminder | AlarmManager |
| Web | Android WebView + Custom Tabs |
| HTML Parser | Jsoup，仅在确认确实需要时加入 |

如果准备提交 Google Play，2026-09-09 时新 App / 更新至少应以 Android 16（API 36）为目标平台。

建议：

```text
minSdk = 26
targetSdk >= 36
```

`minSdk=26` 是项目取舍，不是强制要求；如果需要覆盖更旧手机，可以继续下调。

## 4.2 Android 官方 Skills：纳入 AI 辅助开发工具链

本项目建议将 Android 官方的 `android/skills` 仓库纳入开发参考：

```text
https://github.com/android/skills
```

它的定位是：

> **Android Official Skills = 面向 AI Coding Agent 的 Android 官方、模块化工程知识与工作流。**

Android 官方将这些 Skills 定义为 AI 优化的模块化说明与资源，用于帮助 Agent 按 Android 官方最佳实践执行特定开发任务。Skills 遵循开放的 Agent Skills 规范，并可以通过 Android CLI 安装到支持 Skills 的 AI 开发工具中。

### 为什么本项目值得使用

本项目既包含普通 Android 工程，也包含较容易因平台版本变化而出错的部分：

```text
Compose / Navigation
WebView
Intent / Custom Tabs
通知权限
后台任务
AlarmManager
测试
R8 / Release
Google Play 发布要求
```

使用官方 Skills 的价值主要是：

1. **给 AI Agent 补充较新的 Android 平台知识**，减少模型依赖过时训练知识；
2. **让多步骤 Android 工程任务采用可重复的官方工作流**；
3. **在测试、安全、Navigation、R8、UI 现代化等专项任务上提供官方约束；**
4. 便于以后让 AI Agent 形成“修改代码 → 编译 → 模拟器运行 → 检查 UI → 测试 → 修正”的开发闭环。

### 它不是什么

`android/skills` **不是 App 运行时依赖，也不是学校认证协议工具**。

它不能替代：

```text
Microsoft AD FS / OAuth 官方文档
SIS / STU 的真实网络请求
Chrome DevTools / WebView 调试
Cookie / Session 实机验证
学校第三方接入政策
```

尤其不能因为某个 Skill 给出了通用 Android 写法，就推断：

```text
SIS / STU 一定使用某种 Cookie
AD FS 一定允许 WebView
两个系统一定共享 Session
某个非公开业务接口一定稳定
```

这些结论仍必须来自实际系统行为。

### 当前优先推荐的 Skills

按本项目实际需求，优先级建议如下：

| Skill / 类别 | 优先级 | 本项目用途 |
|---|---:|---|
| `android-cli` | P0 | 创建/运行 App、设备与模拟器操作、UI 检查、Skills 管理 |
| `testing-setup` | P0 | 建立 Unit / UI / Screenshot 等测试策略 |
| `android-intent-security` | P0 | 检查 Intent、外部链接、PendingIntent 等安全边界 |
| Navigation 3 相关 Skill | P1 | 如果项目采用 Navigation 3 |
| `edge-to-edge` | P1 | Compose UI 完善阶段 |
| Android Profiler / Perfetto 相关 Skills | P2 | 性能、卡顿、启动耗时分析 |
| `r8-analyzer` | P2 | Release 阶段检查 R8 / shrink 配置 |
| Play Policy 相关 Skill | 发布前 P0 | 准备 Google Play 分发时做政策审计 |
| AGP 9 Upgrade | 按需 | 只有升级 AGP 时使用 |

> Skills 仓库会持续变化，因此不要把上述列表当成永久固定清单；实际开发时先查看当前可用 Skills。

### 安装和发现

安装 Android CLI 后，可先查看当前 Skills：

```bash
android skills list
```

安装单个 Skill：

```bash
android skills add android-cli --project=.
```

也可以根据实际任务安装其他 Skill，例如：

```bash
android skills add r8-analyzer --project=.
```

如果确实希望安装当前全部 Skills，可以使用：

```bash
android skills add --all
```

但本项目**不建议一开始无差别安装全部 Skills**。优先安装与当前任务相关的 Skill，可以减少无关 Agent 上下文和后续维护成本。

### 建议的 AI Agent 开发闭环

```text
开发需求
   ↓
AI Coding Agent
   ├──────── Android Official Skills
   │             ↓
   │      Android 平台最佳实践
   │
   └──────── 本项目工程规范
                 ↓
        SIS / STU Adapter
        Session Bridge
        Repository / Room
                 ↓
             修改代码
                 ↓
          Gradle 编译 / 测试
                 ↓
       Emulator / Android 真机
                 ↓
      UI / Logcat / Network 验证
                 ↓
           发现问题并修正
                 ↓
              下一轮
```

### 使用原则

在这个项目中采用以下证据优先级：

```text
学校系统真实行为
        >
AD FS / Android 官方协议和平台文档
        >
Android Official Skills
        >
AI Agent 自身已有知识
```

也就是说：

- Android Skills 用来指导“Android 应该怎样正确实现”；
- 实际抓包和服务端响应决定“学校系统实际上怎样工作”。

两者结合，而不是互相替代。

### 建议纳入项目仓库

可以在项目根目录增加：

```text
docs/
├── architecture.md
├── endpoint-notes.local.md
└── agent-development.md
```

其中 `agent-development.md` 记录：

```text
当前安装的 Android Skills
使用的 Android CLI 版本
重要 Agent 约束
哪些结论必须实机验证
```

如果项目主要由“开发者 + AI Coding Agent”共同推进，这一部分应视为正式工程基础设施，而不是临时提示词。

---

# 5. Step 1：先做浏览器侧业务侦察

这一阶段不要写业务 UI。

目标只有一个：

> 找到“用户正常打开课表页面时，浏览器到底发了什么请求”。

## 5.1 使用 Chrome DevTools

分别记录：

```text
SIS：
https://sis.slai.edu.cn/

STU：
https://stu.slai.edu.cn/
```

记录以下信息：

```text
A. 登录前访问 URL
B. 302 / JS 跳转链
C. 登录成功后的最终 URL
D. Cookie 名称
E. Cookie Domain / Path / Secure / HttpOnly / SameSite
F. 课表页面加载产生的网络请求
G. 打卡页面加载产生的网络请求
H. 请求方法 GET / POST
I. Content-Type
J. Request Headers
K. Response Content-Type
L. Response 示例结构
```

## 5.2 不要一上来分析所有接口

只找：

```text
接口 1：获取本人课表
接口 2：获取本人打卡状态
```

其他接口先忽略。

## 5.3 输出一个本地开发文档

例如：

```text
docs/
└── endpoint-notes.local.md
```

该文件加入：

```gitignore
docs/*.local.md
*.har
```

严禁把真实：

- Cookie；
- Token；
- 学号；
- Session ID；
- HAR；

提交到 Git。

---

# 6. Step 2：创建最小 WebView 登录容器

先创建：

```text
feature/web/
├── SchoolWebScreen.kt
├── SchoolWebView.kt
├── SchoolWebViewClient.kt
└── AllowedHosts.kt
```

允许的 Host 初始只包含：

```kotlin
setOf(
    "stu.slai.edu.cn",
    "sis.slai.edu.cn",
    "sts.slai.edu.cn"
)
```

WebView 基础配置：

```kotlin
webView.settings.apply {
    javaScriptEnabled = true
    domStorageEnabled = true

    allowFileAccess = false
    allowContentAccess = false

    setSupportMultipleWindows(false)
}

WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
```

建议：

```text
mixedContentMode = NEVER_ALLOW
```

不要：

```text
onReceivedSslError { handler.proceed() }
```

遇到 SSL 错误应该失败，而不是忽略。

## 6.1 URL 路由规则

```text
slai.edu.cn 白名单内
    → WebView

普通 https 外部链接
    → Custom Tabs / 系统浏览器

http
    → 默认拒绝
```

## 6.2 验收

必须完成：

```text
[ ] SIS 可进入登录页
[ ] STU 可进入登录页
[ ] AD FS 页面正常输入
[ ] 登录后正常跳回业务系统
[ ] Back 键行为正常
[ ] App 冷启动后 WebView Cookie 行为已观察
```

如果 WebView 被 AD FS 明确拒绝：

```text
不要绕过
    ↓
转 Custom Tabs / 系统浏览器门户路线
```

---

# 7. Step 3：实现 Session Bridge

这是整个 Hybrid 方案的技术核心。

目标：

> WebView 完成登录后，原生 OkHttp 在请求目标业务 URL 时，能携带该 URL 当前应使用的 Cookie。

Android WebView 的 CookieManager 可以按照 URL 返回当前适用 Cookie。

---

## 7.1 WebView 作为 Cookie 的主来源

建立：

```text
core/session/
├── WebCookieBridge.kt
├── SessionDetector.kt
├── SessionState.kt
└── SessionStore.kt
```

伪代码：

```kotlin
class WebCookieBridge {

    private val cookieManager = CookieManager.getInstance()

    fun cookieHeader(url: String): String? {
        return cookieManager.getCookie(url)
    }

    fun saveSetCookie(url: String, setCookieHeaders: List<String>) {
        setCookieHeaders.forEach {
            cookieManager.setCookie(url, it)
        }
        cookieManager.flush()
    }
}
```

---

## 7.2 OkHttp 请求时按 URL 读取 Cookie

建议使用 Network Interceptor。

```kotlin
class WebViewCookieInterceptor(
    private val bridge: WebCookieBridge
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url.toString()

        val builder = original.newBuilder()

        bridge.cookieHeader(url)?.let {
            builder.header("Cookie", it)
        }

        val response = chain.proceed(builder.build())

        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            bridge.saveSetCookie(url, setCookies)
        }

        return response
    }
}
```

注意：

> 这段逻辑只能作为“已验证兼容的会话桥”，不能假设任何 slai.edu.cn Cookie 都应该发送给任何 slai.edu.cn 服务。

CookieManager 必须按具体 URL 获取 Cookie。

---

# 8. Step 4：验证“原生读取课表”的最小闭环

现在不要写 Repository。

直接写一个开发者测试按钮：

```text
Developer Screen
└── Test SIS Schedule Request
```

点击：

```text
OkHttp
  ↓
调用刚才浏览器观察到的同一个“本人课表”请求
  ↓
携带 WebView Cookie
  ↓
输出 HTTP status + Content-Type + 脱敏响应摘要
```

---

## 8.1 必须识别的情况

### 成功

```text
HTTP 200
Content-Type 正确
返回课表 JSON / HTML
```

### 会话失效

可能表现为：

```text
HTTP 302 → 登录页
```

也可能是：

```text
HTTP 200
但 body 实际是“登录页面 HTML”
```

因此不能只判断：

```kotlin
response.code == 200
```

需要判断：

```text
final URL
Content-Type
响应结构
业务字段
```

---

## 8.2 建立统一结果类型

```kotlin
sealed interface RemoteResult<out T> {
    data class Success<T>(val data: T) : RemoteResult<T>
    data object SessionExpired : RemoteResult<Nothing>
    data object NetworkUnavailable : RemoteResult<Nothing>
    data class ServerError(val code: Int) : RemoteResult<Nothing>
    data class SchemaChanged(val reason: String) : RemoteResult<Nothing>
    data class UnknownError(val reason: String) : RemoteResult<Nothing>
}
```

---

# 9. Step 5：做技术路线分流

每个系统分别做判断。

```text
                   ┌─ Native API 可稳定复用
登录后读取数据 ────┤
                   ├─ 只能 WebView 内获取
                   │
                   └─ 只能直接看网页
```

给每个系统记录：

```kotlin
enum class IntegrationMode {
    NATIVE_API,
    WEBVIEW_EXTRACT,
    WEB_ONLY
}
```

推荐：

```text
SIS：
优先 NATIVE_API

STU：
根据实际抓包结果决定
```

如果一个系统是 `WEB_ONLY`，也完全可以上线。

不要为了追求“全部原生化”去绕过学校的认证或安全策略。

---

# 10. Step 6：定义统一课表 Domain Model

不要直接让 UI 使用 SIS 原始 JSON DTO。

建立：

```text
domain/schedule/
├── Course.kt
├── ClassOccurrence.kt
├── Semester.kt
└── ScheduleRepository.kt
```

建议最终 UI 只认“具体日期上的课程”。

```kotlin
data class ClassOccurrence(
    val id: String,
    val courseName: String,
    val teacher: String?,
    val location: String?,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val source: ScheduleSource
)
```

这里最关键的是：

```text
原始 SIS 周次规则
    ↓
Normalizer
    ↓
具体日期
```

不要让 Compose 页面自己算单双周、周次或调课。

---

# 11. Step 7：实现 SIS Adapter

目录：

```text
data/sis/
├── SisApi.kt
├── SisDto.kt
├── SisScheduleParser.kt
├── SisScheduleNormalizer.kt
├── SisRemoteDataSource.kt
└── SisSessionDetector.kt
```

流程：

```text
HTTP Response
   ↓
DTO Parser
   ↓
Schema Validation
   ↓
Schedule Normalizer
   ↓
List<ClassOccurrence>
```

## 11.1 Parser 需要“宽松但可校验”

可以允许：

```text
未知字段增加
```

但不能允许：

```text
关键字段消失后仍然默默生成空课表
```

例如：

```kotlin
if (dto.courses == null) {
    return RemoteResult.SchemaChanged("missing courses")
}
```

核心原则：

> “解析失败”绝不能等价于“今天没有课”。

---

# 12. Step 8：Room 本地数据库

建立：

```text
core/database/
├── AppDatabase.kt
├── CourseOccurrenceEntity.kt
├── ScheduleDao.kt
└── SyncMetaEntity.kt
```

核心表：

```text
course_occurrence
-----------------
id
accountHash
courseName
teacher
location
date
startTime
endTime
source
updatedAt
```

同步信息：

```text
sync_meta
---------
accountHash
system
lastSuccessAt
lastAttemptAt
parserVersion
lastError
```

---

## 12.1 原子更新规则

正确：

```text
远程获取
  ↓
完整解析
  ↓
完整校验
  ↓
数据库事务替换
```

错误：

```text
先 DELETE 全部课表
  ↓
然后网络请求
  ↓
解析失败
  ↓
用户课表变成空
```

必须：

> 只有新数据通过完整校验后，才能替换最后一次有效数据。

---

# 13. Step 9：ScheduleRepository 闭环

Repository：

```kotlin
interface ScheduleRepository {
    fun observeToday(): Flow<List<ClassOccurrence>>
    fun observeWeek(): Flow<List<ClassOccurrence>>
    fun observeSyncState(): Flow<SyncState>

    suspend fun refresh(): RefreshResult
}
```

刷新：

```text
refresh()
   ↓
检查网络
   ↓
调用 SIS Adapter
   ↓
Success?
 ┌─Yes──────────────────────────────┐
 │ 校验 → DB transaction → 更新状态 │
 │ → 重排提醒 → 返回 Success         │
 └──────────────────────────────────┘
   ↓No
分类错误
   ├─ SessionExpired → NEEDS_LOGIN
   ├─ Network → 保留缓存
   ├─ SchemaChanged → 保留缓存 + 网页兜底
   └─ ServerError → 保留缓存
```

---

# 14. Step 10：首页与课表 UI

首页推荐只放高频内容：

```text
┌────────────────────────────┐
│ 今天 · 9 月 9 日            │
│                            │
│ 09:00  数值分析             │
│        A201                 │
│                            │
│ 14:00  机器学习             │
│        B403                 │
│                            │
│ 上次同步：13:42             │
│ [刷新]                      │
├────────────────────────────┤
│ 打卡状态                    │
│ 当前：未确认                │
│ [查看学生系统]              │
├────────────────────────────┤
│ [本周课表] [教务系统]       │
└────────────────────────────┘
```

首页永远先读 Room。

因此 App 启动不依赖学校服务器。

---

# 15. Step 11：登录失效闭环

当 Repository 收到：

```text
SessionExpired
```

执行：

```text
sisSessionState = NEEDS_LOGIN
```

UI 显示：

```text
课表数据更新失败
上次成功同步：2026-09-09 13:42

[重新登录]
[继续查看缓存]
[打开教务网页]
```

点击重新登录：

```text
打开 SIS 登录 WebView
   ↓
用户正常完成登录
   ↓
监听页面回到已登录业务域
   ↓
执行 lightweight session probe
   ↓
成功
   ↓
SessionState = AUTHENTICATED
   ↓
立即 refresh()
```

这形成完整恢复闭环。

---

# 16. Step 12：实现 STU 打卡状态

不要先复制 SIS 的实现。

重新独立验证：

```text
登录 STU
  ↓
打开打卡页面
  ↓
观察正常页面请求
  ↓
确定状态数据来源
```

如果存在稳定只读接口：

```text
IntegrationMode.STU = NATIVE_API
```

否则：

```text
IntegrationMode.STU = WEB_ONLY
```

第一版打卡页可以是：

```text
今日打卡
状态：已确认 / 未确认 / 当前无法确认

最后查询：13:45

[刷新状态]
[打开学校打卡页面]
```

关键：

```text
接口失败 ≠ 未打卡
```

失败时必须显示：

```text
当前无法确认
```

---

# 17. Step 13：网页兜底

定义统一网页路由：

```kotlin
sealed class SchoolPage(val url: String) {
    data object SisHome : SchoolPage(...)
    data object SisSchedule : SchoolPage(...)
    data object SisCourseSelection : SchoolPage(...)
    data object StuHome : SchoolPage(...)
    data object StuCheckIn : SchoolPage(...)
}
```

所有原生页面必须有：

```text
“在原网页中打开”
```

如果后端改变：

```text
原生功能坏掉
≠
App 完全不可用
```

这是整个方案可维护性的核心。

---

# 18. Step 14：后台同步

使用 WorkManager。

目标是：

> 尽力保持缓存新鲜，而不是保证某一分钟刷新。

建议：

```text
PeriodicWorkRequest
每若干小时执行一次
仅在有网络时执行
```

Worker：

```text
读取 session state
   ↓
NEEDS_LOGIN?
   ├─ Yes → 直接停止，不后台弹登录
   └─ No
        ↓
      refresh()
        ↓
      Success → 更新 DB
      SessionExpired → 标记 NEEDS_LOGIN
      Network Error → retry/backoff
```

不要：

```text
每 15 分钟疯狂查询校园服务器
```

---

# 19. Step 15：课程提醒

WorkManager 不承担准时上课提醒。

流程：

```text
课表同步成功
   ↓
生成未来 N 天 ClassOccurrence
   ↓
对比现有 reminder
   ↓
取消删除的课程提醒
   ↓
新增/更新 AlarmManager
```

默认可以提供两种提醒模式：

```text
标准提醒
精确提醒
```

精确提醒只有用户明确启用，并且设备允许时才使用。

Android 13+ 通知需要处理通知运行时权限。

设备重启：

```text
BOOT_COMPLETED
   ↓
读取 Room
   ↓
重新安排未来提醒
```

---

# 20. Step 16：安全加固

## 20.1 Manifest

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

如果用户启用精确提醒，再根据 Android 版本处理对应 exact alarm 权限。

Application：

```xml
android:usesCleartextTraffic="false"
```

---

## 20.2 WebView

禁止：

```text
SSL error proceed
任意 addJavascriptInterface
file:// 任意访问
任意域跳转留在 WebView
```

允许 Host 使用严格白名单。

---

## 20.3 日志

Release 不记录：

```text
Authorization
Cookie
Set-Cookie
password
code
access_token
refresh_token
完整 response body
```

建立脱敏器：

```kotlin
fun sanitize(headers: Headers): Headers
```

---

## 20.4 本地安全

如果必须保存额外敏感材料：

```text
Android Keystore
   ↓
生成 App 私有加密 key
   ↓
加密后再持久化
```

同时明确是否允许 Android Backup。

学校会话数据如果不应迁移到其他设备：

```text
从备份中排除
```

---

# 21. Step 17：测试矩阵

至少完成下面这些。

## 21.1 登录

```text
[ ] 第一次登录
[ ] 登录失败
[ ] 用户取消
[ ] 密码错误
[ ] 登录成功
[ ] Session 过期
[ ] App 强制停止后重新启动
[ ] Cookie 被清除
```

## 21.2 网络

```text
[ ] Wi-Fi
[ ] 移动网络
[ ] 无网络
[ ] 请求超时
[ ] DNS 错误
[ ] HTTP 500
[ ] HTTP 403
[ ] HTTP 302 到登录
[ ] HTTP 200 但 body 是登录 HTML
```

## 21.3 课表

```text
[ ] 今天有课
[ ] 今天没课
[ ] 单双周
[ ] 连续多节
[ ] 同一时间冲突课程
[ ] 教室为空
[ ] 教师为空
[ ] 调课
[ ] 解析失败
[ ] 返回字段新增
[ ] 关键字段消失
```

## 21.4 缓存

```text
[ ] 首次无缓存 + 网络失败
[ ] 有缓存 + 网络失败
[ ] 新数据成功覆盖
[ ] 新数据解析失败不覆盖旧数据
[ ] 切换用户不会读取另一个账号缓存
```

## 21.5 提醒

```text
[ ] 通知权限允许
[ ] 通知权限拒绝
[ ] 手机重启
[ ] 课表修改后旧提醒取消
[ ] 系统 exact alarm 权限不存在
```

---

# 22. Step 18：上线前验收标准

SIS 原生课表必须至少满足：

```text
连续刷新：
10 次请求结果与原网页一致

冷启动：
3 次冷启动均能正确读取本地缓存

断网：
仍能查看最后一次课表

Session 失效：
不会显示“无课”，而是明确显示“需要重新登录”

Parser 失败：
旧课表不删除

网页兜底：
任何时候都能一键进入学校原页面
```

STU 打卡状态同理。

---

# 23. 推荐目录结构

```text
app/
├── App.kt
├── MainActivity.kt
└── navigation/

core/
├── database/
├── network/
├── session/
├── security/
├── web/
└── common/

domain/
├── schedule/
├── checkin/
└── session/

data/
├── sis/
│   ├── SisApi.kt
│   ├── SisDto.kt
│   ├── SisScheduleParser.kt
│   ├── SisScheduleNormalizer.kt
│   ├── SisRemoteDataSource.kt
│   └── SisSessionDetector.kt
│
└── stu/
    ├── StuApi.kt
    ├── StuDto.kt
    ├── StuCheckInParser.kt
    ├── StuRemoteDataSource.kt
    └── StuSessionDetector.kt

feature/
├── home/
├── schedule/
├── checkin/
├── login/
├── web/
└── settings/

worker/
├── ScheduleSyncWorker.kt
└── ReminderRescheduleWorker.kt

reminder/
├── ReminderScheduler.kt
├── AlarmReceiver.kt
└── BootReceiver.kt
```

---

# 24. 最关键的状态机

```text
                 ┌─────────────┐
                 │   UNKNOWN   │
                 └──────┬──────┘
                        │ probe
            ┌───────────┴───────────┐
            ↓                       ↓
    AUTHENTICATED              NEEDS_LOGIN
            │                       │
        refresh                    login
            │                       │
       ┌────┴─────┐                 ↓
       ↓          ↓           AUTHENTICATING
    success    session expired      │
       │          │                 ↓
       │          └────────── AUTHENTICATED
       ↓
AUTHENTICATED
```

不要用一个 Boolean 代替它。

---

# 25. 开发顺序

严格按照这个顺序：

```text
1. 创建 Compose 工程
2. 安装 Android CLI，并按需加入 `android-cli` / `testing-setup` / `android-intent-security` 等官方 Skills
3. 浏览器 DevTools 分析 SIS
4. 浏览器 DevTools 分析 STU
5. 建 WebView 登录 Spike
6. 验证 SIS 登录
7. 验证 STU 登录
8. 写 Cookie / Session Bridge
9. 原生请求“一次本人课表”
10. 做 SessionExpired 检测
11. 定义 ClassOccurrence
12. 写 SIS Parser
13. 写 SIS Normalizer
14. 写 Room
15. 写 ScheduleRepository
16. 做今日课程
17. 做本周课程
18. 做登录失效恢复
19. 做网页兜底
20. 独立验证 STU 打卡
21. 可行则原生化打卡状态
22. 加 WorkManager
23. 加 AlarmManager
24. 加通知权限
25. 做安全加固
26. 跑完整测试矩阵
27. 再考虑分发
```

不要调换成：

```text
先画完整 UI
→ 再研究认证
```

认证和原生读取链路必须先通过。

---

# 26. 技术决策树

```text
                    开始
                      │
                      ▼
            WebView 能正常登录？
             │                │
            Yes               No
             │                │
             ▼                ▼
    原生请求能复用会话？    Custom Tabs /
       │          │          Browser Portal
      Yes         No             │
       │          │              ▼
       │          ▼           Web-only
       │    WebView 内能稳定
       │     读取目标数据？
       │       │        │
       │      Yes       No
       │       │        │
       ▼       ▼        ▼
 Native API  WebView   Web-only
   Adapter   Extract
       │       │
       └───┬───┘
           ▼
        Room Cache
           │
           ▼
       Compose UI
           │
           ▼
     Web fallback always
```

---

# 27. 关于标准 OAuth 的长期路线

如果学校愿意给这个 App 正式接入：

```text
学校注册独立 Android Client
   ↓
Authorization Code + PKCE
   ↓
系统浏览器 / Custom Tabs
   ↓
App 自己的 redirect URI
   ↓
获得权限受限的 API Token
   ↓
官方只读 API
```

这是正式分发时最理想的路线。

不要把现有 Web 系统的 `client_id` 和 `redirect_uri` 直接当作 Android App 自己的 OAuth 配置。

---

# 28. MVP 的最终完成定义

满足下面条件时，才算“闭环”：

```text
用户第一次打开
   ↓
登录
   ↓
成功获取课表
   ↓
落地 Room
   ↓
首页显示今日课程
   ↓
关闭 App
   ↓
断网重新打开
   ↓
仍能显示缓存
   ↓
恢复网络
   ↓
自动/手动刷新
   ↓
课表更新
   ↓
生成课程提醒
   ↓
Session 过期
   ↓
App 正确提示重新登录
   ↓
重新认证
   ↓
数据恢复
   ↓
接口临时失效
   ↓
保留旧数据 + 可打开原网页
```

只要这个闭环跑通，项目就已经具备实际可用性。

---

# 29. 最优先的第一个开发任务

不是做首页。

不是做周课表。

不是做打卡。

而是做一个只有两个按钮的 `SpikeActivity`：

```text
[1] 打开 SIS 登录

[2] 测试读取本人课表
```

第二个按钮能够在第一个按钮正常登录后返回真实课表数据，整个方案最核心的不确定性就解决了。

然后再进入正式工程架构。

---

# 30. 官方技术依据

本方案涉及的关键平台约束建议以以下官方资料为准：

1. RFC 8252 — OAuth 2.0 for Native Apps  
   - 原生 OAuth 推荐使用外部浏览器，而不是嵌入式 WebView。

2. Microsoft Learn — AD FS OpenID Connect/OAuth flows and application scenarios  
   - AD FS 的 Authorization Code 场景；
   - AD FS 2019+ 对 PKCE 的支持说明。

3. Android Developers — `CookieManager`  
   - `getCookie(url)` 按 URL 获取 WebView 当前适用 Cookie；
   - `flush()` 将可访问 Cookie 写入持久存储。

4. Android Developers — WorkManager / Define work requests  
   - PeriodicWorkRequest 不是精确定时器；
   - 最小周期为 15 分钟。

5. Android Developers — Schedule alarms  
   - 精确提醒应使用 AlarmManager；
   - Android 12+ / 13+ 精确闹钟权限行为。

6. Android Developers — Notification runtime permission  
   - Android 13+ 的 `POST_NOTIFICATIONS` 运行时权限。

7. Google Play Console — Target API level requirements  
   - 自 2026-08-31 起，新 App / 更新需要 target Android 16（API 36）或更高。

8. Android Developers — Android Skills  
   - https://developer.android.com/tools/agents/android-skills  
   - Android 官方对 Skills 的定位、Android CLI 安装方式、自动激活方式以及自定义 Skills 结构说明。

9. Android 官方 GitHub — `android/skills`  
   - https://github.com/android/skills  
   - 官方 Skills 源仓库；实际可用 Skill 会持续更新，应以仓库和 Android Developers 文档的当前状态为准。
