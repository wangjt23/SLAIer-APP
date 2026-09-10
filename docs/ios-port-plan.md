# SLAIer iOS 适配方案

> 状态：**方案讨论稿**，尚未开始实施。
> 最后更新：2025-09-10

本文回答一个问题：**能不能给 SLAIer 出 iOS 版，怎么做，代价多少。**

---

## 0. 结论先行

| 问题 | 结论 |
|---|---|
| 技术上能不能做 | **能**。而且这个仓库的底子比一般 Android 项目好很多（见 §2） |
| 有没有"iOS 安装包" | **没有**。iOS 不存在 APK 的等价物，见 §1 |
| 最省力的技术路线 | **KMP + Compose Multiplatform**，见 §3 |
| 最大的障碍 | **不是代码，是分发**。见 §5 |
| 最大的单点风险 | WKWebView 能否拿到 SIS 的会话 cookie，见 §6 Phase 0 |
| 已经排除的路线 | **网页版 / PWA**（实测不可行，见 §4） |

---

## 1. 先纠正一个前提：iOS 没有"安装包"

Android 的分发路径是：

```text
出 APK → 丢到 GitHub Release → 同学下载 → 点一下装上 → 完事
零成本 · 零审核 · 零门槛
```

**iOS 上这条路不存在。** 不是难，是没有。原因有三层，缺一不可：

### 1.1 签名墙

`.ipa` 文件本质上只是一个 zip。它和 APK 最大的区别是：**iOS 内核只愿意执行被 Apple 信任的证书签过名的代码**。没有有效签名，`.ipa` 拖进手机什么都不会发生。

Android 允许"未知来源"（用户自己承担风险），iOS 从设计上不允许。

### 1.2 账号墙

能用来签名的证书只有两个来源：

| 来源 | 费用 | 签名有效期 | 设备限制 |
|---|---|---|---|
| Apple ID 免费签名 | 0 | **7 天** | 需电脑重签 |
| Apple Developer Program | **$99 / 年** | 1 年 | 见 §5 |

### 1.3 分发墙

即使签了名，要让**别人的**手机信任它，还得再过一关：

- **App Store**：需要审核 + 中国大陆上架需要 ICP 备案
- **TestFlight**：需要 $99 账号，构建 90 天过期
- **Ad Hoc**：需要收集每台设备的 UDID，**100 台/年**上限

**所以真正的问题不是"怎么做一个 iOS 安装包"，而是"选哪条分发路径"。** 这个选择反过来决定技术方案要不要做、做到什么程度（见 §5、§7）。

---

## 2. 这个仓库的底子（实测数据）

移植成本主要取决于"有多少代码和 Android 绑死"。实际盘查结果：

### 2.1 分层污染度

| 包 | 文件数 | 行数 | 真正依赖 Android 的 |
|---|---|---|---|
| `domain/` | 6 | 834 | **0 个（100% 纯 Kotlin）** |
| `data/` | 24 | 4338 | **1 个**（`ProviderStore.kt`） |
| `core/common` | 8 | 357 | `java.time` / `java.util.Locale` |
| `core/network` | 4 | 283 | OkHttp（JVM-only） |
| `core/session` | 5 | 622 | 少量 |
| `core/web` | 5 | 1039 | **2 个文件**碰 WebView |
| `core/database` | 4 | 428 | Room |
| `feature/` + `ui/` + `navigation/` | 21 | 4833 | Compose（全量） |

**关键发现**：`data/` 里统计上"含 Android 依赖"的 13 个文件，逐个看下来有 **12 个只是 `@Inject` / `@Singleton` 注解**——那是纯 Java 注解，可移植。真正碰 Android SDK 的只有 `ProviderStore.kt` 一个。

### 2.2 代码可复用比例

```text
业务逻辑层   7685 行   ← 可直接共享（domain / data / core / di）
UI 层        5729 行   ← Compose Multiplatform 可共享，或 SwiftUI 重写
─────────────────────
合计        13530 行   逻辑占比 57%
```

### 2.3 三个意外之喜

1. **154 个单元测试零 Android 依赖**（无 Robolectric，零 `android.*` import）。这些测试固化了大量逆向出来的业务规则——周次位掩码、刷卡配对、05:00 失效规则。**它们能原样搬到 iOS 上跑**，这是最值钱的资产。

2. **`WebCookieBridge` 的设计天然跨平台**。当前实现是"显式读出 cookie → 手工塞进请求头"，而不是依赖共享 cookie jar。iOS 上 `WKWebsiteDataStore.httpCookieStore` 有完全对应的读法，**设计可以 1:1 照搬**。

3. **`CaptureScript.kt` 注入的 JS 与平台无关**，可直接在 WKWebView 里复用（用于端点自学习）。

### 2.4 需要替换的依赖

| 现在 | 用途 | iOS 替代 | 难度 |
|---|---|---|---|
| OkHttp 4.12 | HTTP | **Ktor client (Darwin)** | 中，网络层只有 283 行 |
| `java.time.*` | 日期时间 | **kotlinx-datetime** | 中，散布最广（domain 11 处 / data 13 处） |
| Jsoup | HTML 兜底解析 | **Ksoup** | 低，只用在 1 个文件 |
| Room 2.7.2 | 本地库 | **Room KMP**（2.7+ 已支持 iOS）或 SQLDelight | 低 |
| DataStore | 偏好存储 | **DataStore KMP** | 低，只 1 个文件 |
| Hilt 2.57 | 依赖注入 | **Koin** 或手写（Hilt 在 Native 上不可用） | 中 |
| WorkManager + AlarmManager | 后台同步 / 提醒 | **BGTaskScheduler + UNUserNotificationCenter** | **高，能力差很多**（见 §3.4） |
| `android.webkit.*` | 登录 | **WKWebView interop** | 中，集中在 2 个文件 |
| `java.security.MessageDigest` | 账号哈希 | expect/actual + CryptoKit | 低 |
| `java.util.Locale` | 中英切换 | expect/actual 或 kotlinx | 低 |

---

## 3. 推荐技术方案：KMP + Compose Multiplatform

### 3.1 为什么是这个

两条路线：

| 路线 | 共享 | 重写 | 优点 | 缺点 |
|---|---|---|---|---|
| **KMP + Compose Multiplatform** | 逻辑 + **UI 一起共享** | 平台层 | 一套 UI 两端一致，后续维护成本减半 | iOS 上不是 100% 原生观感，interop 有坑 |
| KMP + SwiftUI | 只共享逻辑 | **UI 全部重写** | 观感最原生 | UI 5700 行重写，且以后每个功能都要写两遍 |

对一个**个人维护的开源项目**，第二个选项的长期成本是致命的——每次改需求都要在两个 UI 里各写一遍。所以推荐第一个。

### 3.2 目标结构

```text
SLAIer-APP/
├── shared/                      ← 新增 KMP 模块
│   └── src/
│       ├── commonMain/          ← domain + data + core 逻辑（7685 行搬这里）
│       ├── commonTest/          ← 154 个测试搬到这
│       ├── androidMain/         ← WebView 桥、Room 驱动、WorkManager
│       └── iosMain/             ← WKWebView 桥、BGTaskScheduler
├── androidApp/                  ← 现 :app 瘦身，只留 UI + Android 入口
├── iosApp/                      ← 新增，Swift 壳 + Xcode 工程
└── gradle/libs.versions.toml
```

### 3.3 关键设计：把 WebView 抽象出来

当前 `core/web` 直接依赖 `android.webkit.*`。改造为接口：

```kotlin
// commonMain
interface WebSessionBridge {
    suspend fun loadAndAwaitLogin(url: String): LoginOutcome
    suspend fun cookiesFor(url: String): Map<String, String>
}
```

- `androidMain` → `CookieManager.getCookie(url)`（现有实现）
- `iosMain` → `WKWebsiteDataStore.default().httpCookieStore.getAllCookies {}`

上层 `SisRemoteDataSource` / `StuRemoteDataSource` 完全不用改。

### 3.4 平台能力差距（要有心理准备）

| 能力 | Android | iOS | 影响 |
|---|---|---|---|
| 定时后台同步 | WorkManager，较灵活 | BGTaskScheduler，**由系统决定何时跑**，可能几天不跑 | 课表自动刷新可能不及时，需改为"打开 App 时刷新" |
| 精确时间提醒 | AlarmManager `setExactAndAllowWhileIdle` | `UNCalendarNotificationTrigger` **可以**精确 | 上课提醒没问题 |
| 常驻后台 | 可以 | 基本不行 | 打卡状态无法后台轮询 |

**结论**：提醒功能 iOS 上不是问题；**自动后台同步要降级**为"启动时 + 前台定时"。

---

## 4. 已排除：网页版 / PWA

原本"网页版"是最诱人的方案——零成本、免审核、iOS/Android/桌面通吃、发个链接就能用。

**实测判定：不可行。**

原因：网页版要拿课表和打卡数据，必须带 cookie 跨域请求学校门户。实测学校服务器的响应头：

```text
sis.slai.edu.cn
  Access-Control-Allow-Credentials: true
  Access-Control-Allow-Origin: *          ← 与上一行组合按规范无效
  Access-Control-Allow-Methods: *
  Access-Control-Allow-Headers: *

预检请求 OPTIONS → 403 Forbidden  (Allow: Get, Post)
```

两个致命问题：

1. **`ACAO: *` 配 `Allow-Credentials: true` 是无效组合**。按 Fetch 规范，带凭据的跨域请求要求 `Access-Control-Allow-Origin` 必须是**明确来源**，通配符 `*` 会被浏览器拒绝。这里 nginx 显然是加了一条无差别的 `add_header *`。
2. **`OPTIONS` 预检直接 403**，服务端根本没处理预检。

`stu.slai.edu.cn` 更严格：无任何 CORS 头，且 `x-frame-options: SAMEORIGIN`。

而且这条路即使能走通，也**必须依赖学校改 nginx 配置**——不可控。

> 附带一提：`ACAO: *` + `Allow-Credentials: true` 本身是个配置瑕疵。在这里不构成实际漏洞（浏览器会拒绝），但学校如果哪天收紧配置，属于正常修复。

**所以：iOS 版必须是原生 App + WebView 登录。**

---

## 5. 分发路径对比 ⚠️ 决定成败的一环

### 5.0 先说结论

```text
「零成本」+「给几十个同学用」
────────────────────────────────
在 iOS 上这五个字组合起来 = 不可行
```

不是难，是**数学上不成立**：免费签名只有 7 天有效期。你自己的手机每 7 天重签一次没问题，但**签名与设备绑定，你无法替同学做**。于是每个同学都得自己续签——按行业经验，**能坚持过第二周的用户只有 5%~15%**。

给 50 个同学装，一个月后大约还剩 **3~8 个人在用**。这不是分发方案。

### 5.1 全部路径对比

| # | 路径 | 成本 | 能覆盖的人数 | App 能活多久 | 结论 |
|---|---|---|---|---|---|
| 1 | **Ad Hoc**（付费账号） | $99/年 | **≤100 台 iPhone/年** | **1 年** | ✅ 小范围最稳 |
| 2 | **TestFlight**（同一账号） | $0 额外 | **10,000 人** | **90 天/构建** | ✅ 体验最接近 Android，**但备案存疑** |
| 3 | 免费签名（SideStore / AltStore） | **$0** | 有耐心的：现实中 5~20 人 | **7 天** | ⚠️ 只能当"极客选项"，不能当方案 |
| 4 | TrollStore | $0 | 只在特定 iOS 版本上可用 | 永久 | ⚠️ 版本受限，大部分同学用不了 |
| 5 | ~~网页版 / PWA~~ | — | — | — | ❌ **已实测排除**，见 §4 |
| 6 | App Store + 备案 | $99 + 备案 | 全国 | 永久 | ❌ **个人走不通**，见 §5.3 |
| 7 | ~~企业账号~~ | $299 | — | — | ❌ **个人没资格，滥用会被封号** |
| 8 | ~~第三方签名~~ | ¥10~30/台 | — | 随时掉签 | ❌ **绝对不要**，见 §5.4 |
| 9 | ~~越狱 / EU 侧载~~ | — | — | — | ❌ 前者不现实，后者**在中国大陆不存在** |

### 5.2 推荐路线：众筹 $99 → TestFlight

**这是唯一能同时满足"给几十个人用"和"体验接近 Android"的路径。**

| 维度 | 体验 |
|---|---|
| 同学要做什么 | 点一个链接 → 装 TestFlight → 点安装。**不用交 UDID，不用电脑，不用每周折腾** |
| 你怎么发版 | 推个 tag → CI 自动出包 → 上传 TestFlight |
| 维护动作 | **每 90 天重新上传一次**（可自动化，见下） |
| 成本 | $99/年，**几十人分摊 = 每人每年 ¥15~20** |

对比 Ad Hoc：TestFlight **不用收集 UDID**，这一点省掉的沟通成本远大于一切。Ad Hoc 适合"就是自己班里 20 个人"，TestFlight 适合"想让同学随便装"。

**90 天过期不是问题**：你已经有 `.github/workflows/release.yml` 了，加一个定时 workflow 每 80 天自动重新构建并上传，这件事可以完全自动化。

**⚠️ 一个未解的关键风险**：Apple 明确要求的是 **App Store 上架**需要备案号。**TestFlight 构建是否需要，无法从公开资料确认**——社区里大量开发者在中国大陆跑 TestFlight 而没有备案，但这属于合规灰区。

> **这一条必须在充值 $99 之前先确认**，问 Apple Developer Support 或备案代理。如果不幸需要备案，TestFlight 这条路的成本会瞬间抬高到和上架一样。

### 5.3 为什么 App Store 走不通（不是难，是资质问题）

三个独立的坎，任何一个都能卡死：

1. **ICP 备案**：工信部《关于开展移动互联网应用程序备案工作的通知》自 2023 年起强制，Apple 于 **2023-09-29** 起对中国大陆区上架要求备案号。**免费 App 同样要备案**——这是经营许可，不是收费规则。
2. **教育类可能属于前置审批**：涉及教育/网络教育的 App 需先取得主管部门同意才能备案。一个读课表和考勤的校园 App 很可能落进这一类。
3. **Apple 审核 5.2.1（第三方授权）**：你使用的是学校的系统、很可能还有学校的名称与标志，却**没有学校书面授权**。这一条本身就足以被拒。

而且：个人开发者账号在 App Store 上**会公开显示你的真实姓名**。对个人项目这是额外的隐私代价。

**唯一的解锁方式是学校出面。** 学院/研究生院如果愿意背书，$99、备案、审核授权**三个问题一起解决**，甚至可能申请到 Apple 的**非营利/教育费用豁免**。代价是项目从"个人开源"变成"半官方"。

### 5.4 为什么绝对不能走第三方签名（超级签/企业签/TF 签）

这一条要说重话：**这类服务的商业模式就是"拿你的二进制去重新签名"，而重签意味着他们可以改你的代码。**

对一个**处理同学账号密码和考勤记录**的 App，把二进制交给匿名签名商，等于把全班同学的学校账号交给陌生人——注入一个偷 cookie 的代码是举手之劳。

除此之外：

- **掉签是常态而非意外**：Apple 撤销证书后，**所有已安装的 App 立刻全部打不开**，用户必须重装
- 企业证书**只允许发给本组织员工**，对外分发是 DPLA 最严重的违规——后果是证书撤销 + 封号 + **永久禁止重新注册**
- 这些服务的预付款、链接、售后基本都是不可靠的
- 2023 年备案新规之后，用企业签分发给大陆用户，本身还是"未备案 App"

**省下的钱，和风险完全不成比例。**

### 5.5 关于免费签名路线的一个安全性说明

值得一提的是：**AltStore / SideStore 用的是用户自己的 Apple ID**，不是你的。所以你的开发者账号不会被牵连，每个同学是在给自己签名。

这是这类工具唯一让人安心的地方。但 7 天续签的门槛不会因此消失。

### 5.6 来源与可信度

本节结论大部分来自公开资料的交叉核对，需要标注 **研究环境限制**：本次研究所在环境**无法打开网页正文**（只能看到搜索索引返回的 URL 和标题），因此下列内容需要按标注理解：

- **✅ 已交叉印证**：`.ipa` 无签名不可安装的机制、备案制度的存在与生效时间、企业账号不对个人开放、EU 侧载不适用于中国大陆、7 天/100 台/90 天等基本数字
- **⚠️ 单一来源或行业惯例**：¥688 的国区定价、TestFlight 的 100/10000 上限、备案办理时长
- **❌ 未能核实（高影响）**：**TestFlight 是否需要备案**、个人主体能否为"无服务器 + 教育相关"的 App 完成备案、Ad Hoc 移除设备是否释放名额

主要参考：

- [Apple 会员资格对比](https://developer.apple.com/support/compare-memberships/)
- [Apple：设备管理概述（100 台上限）](https://developer.apple.com/cn/help/account/devices/devices-overview/)
- [Apple：D-U-N-S 编号（企业账号资质要求）](https://developer.apple.com/cn/help/account/membership/D-U-N-S/)
- [Apple：TestFlight 概述](https://developer.apple.com/cn/help/app-store-connect/test-a-beta-version/testflight-overview/)
- [ITHome：Apple 中国区上架需备案](https://www.ithome.com/0/722/535.htm)
- [天翼云：存量移动互联网应用程序备案通知](https://www.ctyun.cn/notice/detail/10501134)
- [opa334/TrollStore（版本支持矩阵以此为准）](https://github.com/opa334/TrollStore)
- [SideStore 官方 FAQ](https://docs.sidestore.io/zh/docs/faq)
- [WebKit Bug 279153：iOS 18 无法指定 SameSite=None](https://wiki.webkit.org/show_bug.cgi?id=279153)

---

## 6. 建议的实施顺序

### Phase 0 — 可行性验证（1~2 天）⭐ 先做这个

**只验证一个风险点**：iOS 上能不能把整套数据链路跑通。

1. 装 Xcode（当前机器只有 CommandLineTools，**没有 iOS SDK**）
2. 建 KMP 骨架 + `iosApp`
3. WKWebView 打开 SIS 登录页 → 登录 → 读 `httpCookieStore` 拿到 `JSESSIONID`
4. `URLSession` 带该 cookie POST `/kbcx/xskbcx_cxXsKb.html?gnmkdm=index` → 看能否返回 `kbList`

**这一步过了，整个方案成立；过不了，后面都不用做。** 产出是一个跑在模拟器上的丑壳子。

### Phase 1 — 逻辑层共享（1~2 周）

- 抽出 `:shared` 模块，`domain` / `data` / `core` 逻辑搬进 `commonMain`
- 依赖替换（§2.4 表格）
- `WebSessionBridge` 抽象 + 两端实现
- 154 个测试搬到 `commonTest`，在 `jvmTest` 和 `iosSimulatorArm64Test` 双端跑
- 产出：iOS 上能 headless 拉到课表和打卡记录

### Phase 2 — UI（2~4 周）

- `feature/` / `ui/theme/` / `navigation/` 搬进 `commonMain`
- Material3 → CMP Material3（基本 1:1，品牌色已经是自定义的，不受影响）
- 登录页 WKWebView interop
- 后台与通知按 §3.4 降级
- 产出：功能对齐的 iOS App

### Phase 3 — 分发

按 §5 选定的路径执行。

**总计：约 4~8 周业余时间。**

### 可选：先出一个 MVP

如果不想一次投这么多，可以先砍到一个最小可用版本：

```text
只看课表 + 只看打卡记录 + 登录
砍掉：诊断页、主题切换、Provider 层、提醒、桌面小组件
```

这样 Phase 2 能压到 1 周左右，先验证"iOS 上到底好不好用"，再决定要不要补全。

---

## 7. 决策记录

### 7.1 已确认

| 问题 | 决定 | 备注 |
|---|---|---|
| 给谁用 | 自己 + 一些同学（几个人到几十人） | |
| 预算 | **希望零成本** | ⚠️ 与上一行冲突，见 §7.2 |
| 功能范围 | 一次做全功能对齐 Android | 工期 4~8 周 |

### 7.2 ⚠️ 待解决的核心矛盾

**「零成本」和「给几十个同学用」不能同时成立**（§5.0）。必须放弃一个：

| 选择 | 放弃什么 | 结果 |
|---|---|---|
| **A. 众筹 $99 → TestFlight**（推荐） | 放弃"零成本"，每人每年 ¥15~20 | 几十人正常使用，体验接近 Android |
| **B. 坚持零成本** | 放弃"给几十人用"，现实预期降到 5~20 人 | 且每人要折腾一次 + 每 7 天续签 |
| **C. 学校出面** | 放弃"纯个人项目" | $99 + 备案 + 授权一起解决，上限最高 |
| **D. 只给自己用** | 放弃"给同学用" | 零成本可行，SideStore 自签 |

### 7.3 仍需拍板

1. **§7.2 选哪条？**（这个不定，后面无法开工）
2. UI 用 Compose Multiplatform 共享，还是 SwiftUI 原生重写？（§3.1 建议前者）
3. 能不能接受 iOS 上后台同步能力弱于 Android？（§3.4）

### 7.4 建议的执行顺序 ⭐

**关键：不要在验证分发之前投入 4~8 周写代码。**

```text
Phase 0-A  技术验证   装 Xcode → WKWebView 登录 SIS → 拉课表      $0    1~2 天
Phase 0-B  分发验证   拿 Hello World 走通选定路径，
                      让一个真实同学装上，用到第 8 天还活着       见 §7.2  1 天
          ─────────────────────────────────────────────────────
          ⬆ 这两步过了，才值得开始写那 13530 行里的 iOS 部分
          ─────────────────────────────────────────────────────
Phase 1    逻辑层共享  :shared 模块 + 154 测试双端跑              1~2 周
Phase 2    UI          Compose Multiplatform 搬 UI               2~4 周
Phase 3    分发        签名 / TestFlight / 文档                   —
```

**Phase 0-A 现在就能做，不需要任何账号、不花钱。** 建议从这里开始。

---

## 附：本文档的数据来源

所有代码统计数据来自对仓库的直接盘查；CORS 结论来自对 `sis.slai.edu.cn` / `stu.slai.edu.cn` 的实际 HTTP 探测。
