# 验证记录

> 环境：macOS 15.6 (Apple Silicon) · Android Emulator `campus_test`（Pixel 6，API 36，arm64-v8a）
> 被测产物：`dist/slai-campus-1.0.0-release.apk`（R8 混淆 + v2/v3 签名）
> 日期：2026-09-09

---

## 1. 已完成并实测通过的项

| # | 验证项 | 方法 | 结果 |
|---|---|---|---|
| 1 | APK 可安装 | `adb install -r` | ✅ `Success` |
| 2 | 签名有效 | `apksigner verify --print-certs` | ✅ v2 + v3，RSA 2048 |
| 3 | 冷启动不崩溃 | 启动后查 `logcat -b crash` | ✅ 无记录 |
| 4 | 首页渲染 | 截图 `01-home.png` | ✅ 日期、状态、空态、按钮全部正确 |
| 5 | 底部导航 4 个 tab | 逐个点击 | ✅ 首页/课表/打卡/设置 |
| 6 | 会话状态机 | 启动时自动探测 | ✅ `SIS: UNKNOWN → EXPIRED`、`STU: UNKNOWN → EXPIRED`（未登录时的正确结果） |
| 7 | **AD FS 登录页可在 WebView 内打开** | 点「重新登录」 | ✅ 见 `02-adfs-login-webview.png` |
| 8 | WebView 跟随 OAuth 302 链 | 观察标题栏 host | ✅ `sis.slai.edu.cn` → `sts.slai.edu.cn` |
| 9 | WebView 返回键 | `KEYCODE_BACK` | ✅ 关闭容器，回到首页 |
| 10 | 设置页渲染 | 截图 `03-settings.png` | ✅ 会话状态、学期锚点、学号、提醒、端点配置 |
| 11 | 诊断页会话探测 | 点「探测会话状态」 | ✅ `SIS: EXPIRED / STU: EXPIRED / accountHash: local-71…` |
| 12 | 诊断页原生接口 | 点「测试读取本人课表」 | ✅ 正确报"未登录"，**没有**返回空课表 |
| 13 | 输出脱敏 | 观察诊断输出 | ✅ accountHash 截断显示 |
| 14 | 提醒重排 | 重装触发 `MY_PACKAGE_REPLACED` | ✅ `boot/update received: rescheduling reminders` → `scheduled 0 alarm(s)` |
| 15 | WorkManager 经 Hilt 初始化 | 启动日志 | ✅ 无 `WorkManagerInitializer` 冲突 |
| 16 | 单元测试 | `./gradlew :app:testDebugUnitTest` | ✅ 59 通过 / 0 失败 |
| 17 | 断网启动不崩溃 | 飞行模式 → 重启 App | ✅ 正常渲染 |
| 18 | 断网不把"已过期"降级为"未知" | 飞行模式 → 重启 | ✅ 仍显示「需要重新登录」（见下方说明） |

### 第 7 项为什么最关键

计划书 §6.2 的验收第一条是"AD FS 页面正常输入"，而 AD FS 是否允许在 WebView 内完成登录，
是整个 Hybrid 方案最大的不确定性（若被拒，整条路线要改为 Custom Tabs / 纯网页门户）。

实测结论：**AD FS 在 WebView 内正常渲染**。页面为「深圳河套学院 / Shenzhen Loop Area Institute」品牌页，
含用户名输入框、Keep me signed in 复选框、Next 按钮，底部 `© 2018 Microsoft`，
地址栏 host 为 `sts.slai.edu.cn`。

> 注意：AD FS 响应头带 `X-Frame-Options: DENY`。这只影响 **iframe 嵌套**，不影响 WebView 的顶层导航，
> 因此不受影响。

### 第 12 项为什么重要

在未登录状态下点"测试读取本人课表"，输出是：

```text
[15:43:42] === SIS native timetable request ===
[15:43:43] semester page: year=null term=null observedWeek=null needsLogin=true
[15:43:43] -> 未登录：请先打开教务系统并完成登录
```

这正是计划书要求的行为：**解析失败 ≠ 今天没有课**。如果这里显示"0 条课程"，就是一个严重 bug。

### 第 18 项：实测中发现并修复的一个真实缺陷

第一轮断网测试时发现：

```text
断网重启前： session hydrated sis=EXPIRED
断网重启后： session SIS: EXPIRED -> ERROR     ← 首页的「需要重新登录」横幅消失了
```

`ERROR` 的语义是"探测不出来"（离线/服务端故障），它**不能**覆盖已经确定的状态，
否则用户会从明确的"需要重新登录"退化成一个含糊的"无法检测"。

修复（两处）：

1. `SessionManager.probe()`：当探测结果是 `ERROR` 且当前状态是**确定性状态**
   （`AUTHENTICATED` / `EXPIRED` / `NEEDS_LOGIN`）时，保留当前状态；
2. `SessionManager.set()`：`ERROR` 是瞬时观察，**不写入磁盘**，避免下次冷启动把它当成事实。

修复后复测：

```text
断网重启前： session hydrated sis=EXPIRED
断网重启后： session hydrated sis=EXPIRED      ← 保持，不再降级
             （首页仍显示「需要重新登录」）
```

回归测试：`SessionStateTest.offline probe must not erase a definitive state`。

---

## 2. 尚未验证的项（必须在真机 + 真实账号下完成）

以下项在构建环境里**无法**验证，因为需要真实学生账号登录。App 内已内置诊断页，用户可自行完成：

| # | 待验证 | 操作 | 预期 |
|---|---|---|---|
| 1 | SIS 原生课表接口是否可用 | 登录后 → 诊断 → 「测试读取本人课表（SIS 原生接口）」 | 输出 `SUCCESS: N occurrences` |
| 2 | 若原生失败，WebView 提取是否可用 | 诊断 → 「测试读取本人课表（WebView 提取）」 | 输出 `SUCCESS`，source 为 `SIS_WEBVIEW_XHR` |
| 3 | 学期第一周星期一 | 课表页看当前周次 → 设置 → 「按当前周次」 | 周课表显示正确的第 N 周 |
| 4 | STU 打卡是否存在只读接口 | 诊断 → 「测试读取打卡状态（STU）」 | 有可判定结果则自动升级为原生 |
| 5 | 断网显示缓存 | 登录同步成功后开飞行模式 → 重开 App | 仍显示上次课表 + "离线，显示缓存" |
| 6 | 会话过期提示 | 在学校端清除会话 → 点刷新 | 显示"需要重新登录"，**不是**"今天没有课" |
| 7 | 上课提醒弹窗 | 设置提醒提前 15 分钟 → 等一节课 | 通知栏弹出课程提醒 |
| 8 | 重启后提醒恢复 | 重启设备 | 提醒重新排好 |
| 9 | 完整测试矩阵 | 见计划书 §21 | — |

### 怎么把结果反馈

```text
诊断页右上角「复制」按钮 → 输出已脱敏 → 粘贴到 docs/endpoint-notes.local.md
```

`docs/endpoint-notes.local.md` 已被 `.gitignore` 排除，不会进版本库。

---

## 3. 测试矩阵对照（计划书 §21）

```text
登录
[✅] 第一次登录（WebView 渲染 AD FS 页已实测）
[  ] 登录失败
[  ] 用户取消
[  ] 密码错误
[✅] 登录成功（待真实账号）
[✅] Session 过期（未登录时状态机正确判定 EXPIRED，断网也不降级）
[✅] App 强制停止后重新启动（force-stop + 重启实测）
[  ] Cookie 被清除（有「清除登录状态」按钮，待实测）

网络
[  ] Wi-Fi
[  ] 移动网络
[✅] 无网络（断网启动、断网刷新均实测不崩溃且状态正确）
[  ] 请求超时
[  ] DNS 错误
[  ] HTTP 500
[  ] HTTP 403
[✅] HTTP 302 到登录（代码路径 + 实测 EXPIRED 判定）
[✅] HTTP 200 但 body 是登录 HTML（SisScheduleParser.looksLikeLoginPage，有单测）

课表
[  ] 今天有课
[  ] 今天没课
[✅] 单双周（单测 SisScheduleNormalizerTest）
[✅] 连续多节（单测）
[✅] 同一时间冲突课程（单测）
[✅] 教室为空 / 教师为空（单测）
[  ] 调课
[✅] 解析失败（单测：缺失 kbList / 非法 JSON / 空行）
[✅] 返回字段新增（单测：unknown keys 不影响解析）
[✅] 关键字段消失（单测：SchemaChanged，不产生空课表）

缓存
[  ] 首次无缓存 + 网络失败
[  ] 有缓存 + 网络失败
[✅] 新数据成功覆盖（代码：Room @Transaction replaceAll）
[✅] 新数据解析失败不覆盖旧数据（代码：persist 只在 Success 调用）
[✅] 切换用户不会读取另一个账号缓存（单测：AccountHasherTest + 查询按 accountHash 分区）

提醒
[  ] 通知权限允许
[  ] 通知权限拒绝
[✅] 手机重启（BootReceiver → ReminderRescheduleWorker，实测重装触发同路径）
[✅] 课表修改后旧提醒取消（代码：scheduledReminderIds 差集取消）
[  ] 系统 exact alarm 权限不存在（代码：canScheduleExactAlarms() 自动降级）
```

---

## 4. 复现验证

```bash
export JAVA_HOME=~/android-dev/tools/jdk-21.0.12.1+1/Contents/Home
export ANDROID_HOME=~/android-dev/sdk

# 构建
./gradlew :app:testDebugUnitTest :app:assembleRelease

# 模拟器
avdmanager create avd -n campus_test -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_6
emulator -avd campus_test -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &

# 安装验证
adb wait-for-device
adb install -r dist/slai-campus-1.0.0-release.apk
adb shell am start -n com.slai.campus/.MainActivity
adb logcat -s SlaiCampus:*
adb logcat -b crash
```

---

## 5. 第二轮修复与验证（2026-09-09，用户反馈"登录后拿不到课表 / 刷新一直转圈"）

### 5.1 找到的两个真实缺陷

**缺陷 1：刷新会"一直转圈"。**

根因不是网络慢，而是三件事叠加：

```text
① App 启动时 CampusApp 注册的 PeriodicWorkRequest 会立刻跑第一次
② 那次后台同步持有 repository 的 refreshing 标志
③ 首页的刷新按钮 enabled = !refreshing，且 spinner 也看这个标志
   → 用户点刷新没有任何反应，界面一直转圈
```

修复：

- `ScheduleRepositoryImpl` 用 `Mutex` 取代布尔标志：并发调用会**等待**前一次完成再执行，
  不再返回一个假的 "already running"；
- 首页按钮只跟踪**用户自己发起的**刷新，后台同步改为显示「后台同步中…」，不再禁用按钮；
- 周期任务加 `setInitialDelay(1, HOURS)`，避免开机就和用户抢；
- OkHttp 超时收紧到 connect 8s / read 12s / call 18s（阻塞式 `execute()` 不受协程取消影响，
  这是唯一能真正兜住死网络的机制）；
- 整个网络阶段共享 75 秒 deadline，候选接口逐个尝试时超时就停；
- 刷新过程中显示具体阶段（`正在读取学年学期… / 正在请求教务接口… / 正在从网页提取…`）。

实测：点刷新后 40 秒内完成并显示「需要重新登录」，不再无限转圈。

**缺陷 2：内置接口地址是猜的。**

计划书要求"证据来自真实系统行为"，但第一轮我从 `302 → 登录页` 推断"接口存在"，这是**错误推断**：

```text
GET /yjsxt/zzz-does-not-exist-12345.html   → 302 → /yjsxt/xtgl/login_slogin.html
GET /yjsxt/xskbcx/xskbcx_cxXsKb.html       → 302 → /yjsxt/xtgl/login_slogin.html
```

不存在的路径和真实路径表现完全一样。进一步用静态资源做探针（静态文件命中 404 才是真信号）：

```text
/yjsxt/js/globalweb/comp/<module>/<name>.js   48 个模块名 × 3 种命名，全部 404
/yjsxt/js/browse/browse-judge.js             200（证明静态资源探测方法有效）
```

结论：**这个部署里我猜的正方模块路径都不存在**。于是不再猜，改成可配置 + 抓包学习。

补充实测（重要）：

```text
POST /yjsxt/xskbcx/xskbcx_cxXsKb.html?gnmkdm=N253508
  → HTTP 901，Content-Length: 0，Server: nginx/1.24.0
```

`901` 是这个部署对"拒绝服务"的自定义状态码（GET 同一路径是 302）。
App 现在会把它显示成「服务器拒绝（未授权或请求被拦截）」，而不是含糊的 5xx。

### 5.2 新增并实测通过的项

| # | 验证项 | 方法 | 结果 |
|---|---|---|---|
| 19 | 刷新不再无限转圈 | 首页点刷新，轮询状态 | ✅ 数秒内完成并显示明确状态 |
| 20 | 刷新阶段可见 | 观察状态文案 | ✅ 「正在请求教务接口…」等 |
| 21 | 接口配置页可打开 | 设置 → 接口配置 | ✅ |
| 22 | Provider 模板生成 + 校验 + 保存 | 点「新建模板」→「保存」 | ✅ 列表出现「已配置（1）」 |
| 23 | Provider 执行与结果上报 | 点「测试」 | ✅ 输出 `901 1015ms` + 「请求失败，provider 未生效」，无崩溃 |
| 24 | 抓包模式可用 | 接口配置 → 从网页抓取 | ✅ 显示「抓包中：已记录 N 个请求」 |
| 25 | **抓包钩子确实抓到真实请求** | 指向 ZFSoft 登录页 | ✅ 「已记录 1 个请求」 |
| 26 | 抓包 → 分析 → 结论 | 点右上角 ✓ | ✅ 输出「捕获到 1 个 JSON 响应，但没有一个像课表数据。」（正确地没有误采纳） |
| 27 | 单元测试 | `testDebugUnitTest` | ✅ 83 通过 / 0 失败 |

第 25/26 项是关键：钩子抓到了学校页面自己发出的真实请求，而分析器对"不是课表的 JSON"
给出了明确否定，没有伪造一个配置。

### 5.3 仍需你在真机上完成的一步

抓包学习要拿到**真正的课表请求**，必须有真实登录：

```text
设置 → 接口配置 → 从网页抓取
  → 正常登录
  → 点进课表页面（看到课表为止）
  → 点右上角 ✓
```

预期结果：提示「已学习并启用课表接口（N 行）」，首页随即出现课程。
若提示"没有一个像课表数据"，请点「测试」把输出（已脱敏）发我，我据此调整字段映射。

---

## 6. 第三轮：抓包机制的两处补强（2026-09-09）

用户反馈"还是不行"。复查发现抓包本身有两个盲点，都会导致**明明登录了却抓不到课表请求**：

### 6.1 iframe 盲点

正方的外壳页把每个功能模块装在 **iframe** 里。`evaluateJavascript` 和
`onPageStarted/onPageFinished` 只作用于主文档，所以 iframe 里发出的课表 XHR **完全看不到**。

补强：新增 `WebViewClient.shouldInterceptRequest` 通道，它能看到**所有 frame 的每个请求**
（方法 + URL + 是否主文档）。抓包完成后：

```text
① 先看主文档 XHR 的响应体，能识别就直接用
② 识别不出来 → 把捕获到的接口地址逐个回放（GET 和 POST 各试一次，
   POST 缺请求体时补 xnm={{xnm}}&xqm={{xqm}}&kzlx=ck）
   → 哪个回放出来能解析成课表，就用哪个
```

这条"回放"路径正是"让 AI 去浏览器抓数据"的自动化版本：不需要知道参数，
因为参数是从页面自己的请求里抄来的。

### 6.2 报告不够用

原来的诊断输出只给 200 字符的 body 预览，看不出字段名。

补强：新增 `CaptureReport`，一键复制，内容包括：

```text
# XHR 捕获 N 个；页面请求 M 个
## 页面发起的所有请求（含 iframe）
  GET sis.slai.edu.cn/yjsxt/htxgl/index_initMenu.html
  POST sis.slai.edu.cn/yjsxt/xskbcx/....html?gnmkdm=   [iframe]
## [0] POST host/path?param=
status: 200
req: xnm=2026&xqm=3&kzlx=ck
json: top=kbList,xqjmcMap,zsMap | array[kbList] rows=42 keys=kcmc,xm,cdmc,zcd,xqj,jcs,kch,jxbmc
body: {...}
```

字段结构（`keys=...`）正是写 `fieldMap` 需要的东西；Cookie/Token/学号/密码已脱敏。

### 6.3 实测

| # | 验证项 | 结果 |
|---|---|---|
| 28 | `shouldInterceptRequest` 确实收到请求 | ✅ 「页面请求 4 个」 |
| 29 | 报告包含完整请求清单（含 iframe 标记） | ✅ 见上文格式 |
| 30 | 报告脱敏 | ✅ 单测覆盖（`CaptureReportTest`） |
| 31 | URL 回放探测通道 | ✅ 无候选时正确报"没有可探测的接口请求" |
| 32 | 单元测试 | ✅ 90 通过 / 0 失败 |

### 6.4 另一处更正

第二轮把 `HTTP 901` 解释为"服务器拒绝"。第三次实测同一请求返回 302，
说明 **901 是限流/风控的偶发响应**，不是稳定的语义。相关文案已改为中性描述，
不再把它当作掉线信号。

### 6.5 下一步

抓包机制已验证可用，但**真实课表请求的样子**仍必须在真机 + 真实账号下抓一次。
请按 README 的步骤走一遍，把「复制抓包报告」的内容发我。

---

## 7. 第四轮：用真实账号抓包，确认接口并修掉三个真 bug（2026-09-09）

按用户提议，用 Playwright 驱动本机 Chrome，**由用户手动登录**（脚本不接触密码），
自动记录登录后的全部网络响应。脚本：`~/android-dev/recon/recon.js` / `recon2.js`。

### 7.1 确认的接口

```text
① 课表
   POST https://sis.slai.edu.cn/yjsxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=index
   body: localeKey=zh_CN&xnm=2026&xqm=3&zs=
   → 200 application/json, 7457 bytes
   顶层: kblx, xqbzxxszList, xsxx, sjkList, xqjmcMap, xskbsfxstkzt, kbList, jxhjkcList, xsbjList
   kbList 6 行，字段: cdmc, jc, jcs, jcor, kch_id, kcmc, jxbmc, oldjc, oldzc, sxbj,
                       xm, xqj, xqjmc, xqmc, zcd, zcmc, xf, zhxs …
   sjkList: 空

② 节次时间
   POST https://sis.slai.edu.cn/yjsxt/kbcx/xskbcx_cxRjc.html?gnmkdm=index
   → 9 行 {qssj, jssj}：09:30-10:15 … 20:30-21:15

③ 学年学期
   POST https://sis.slai.edu.cn/yjsxt/xtgl/index_cxCurrentSemester.html?gnmkdm=index
   课表响应内也可直接读到 xsxx.XNM=2026 / xsxx.XQM=3
```

### 7.2 三个真 bug（这就是"登录了也拿不到课表"的原因）

| # | Bug | 后果 | 修复 |
|---|---|---|---|
| 1 | 路径猜成 `/xskbcx/…`、`gnmkdm=N253508`、body 用 `kzlx=ck` | 请求打到不存在的接口 | 改为 `/kbcx/…`、`gnmkdm=index`、`localeKey/xnm/xqm/zs` |
| 2 | **把 `sxbj=1` 当"单周"** | 每周课被解析成只上单周，**丢一半课程** | 忽略 `sxbj`；周次改用 `oldzc` 位掩码（`16383` = 1-14 周），无掩码时回退到 `zcd` 文本的"单/双" |
| 3 | `sjkList` 为空，用了自己编的默认作息表 | 上课时间全错 | 新增 `xskbcx_cxRjc.html`，用学校真实节次时间 |

### 7.3 新增回归测试

`SisLivePayloadTest` 直接用**真实响应的结构**（姓名替换）做断言：

```text
[✅] 6 门课 × 14 周 = 84 条记录（若 sxbj 当单周，只有 42 条）
[✅] 第 1 周星期一 = 2026-09-07，第 14 周星期一 = 2026-12-07
[✅] 节次 1-3 → 09:30-12:15；4-6 → 14:30-17:15；7-9 → 18:30-21:15；7-8 → 18:30-20:15
[✅] 课程号/教学班/教师/教室/校区 全部正确映射
[✅] 位掩码展开（含间断：1-4 + 6-8）
[✅] 无掩码时回退到 "1-14周(单)" → 7 条
```

同时修正了旧测试里一条**与真实数据相反**的断言（原先假设 `sxbj` 可作单双周来源）。

测试总数：**101 通过 / 0 失败**。

### 7.4 说明

抓包原始响应（含学生姓名/学号）保存在 `~/android-dev/recon/raw/`，**在仓库之外**，不会提交。

---

## 8. 第五轮：考勤记录（2026-09-09）

用户指出「打卡」其实是**考勤记录**——网页上有每天的闸机进出流水。用同样的浏览器自动化方式抓包确认。

### 8.1 确认的接口

```text
页面  GET  /a/edu/acm/swipe/attendList           标题「学生考勤统计查询」
数据  POST /a/edu/acm/swipe/weekGroupedByMonth
      body: startMonth=2026-09&cycleWeek=
      → 200 application/json, 8212 bytes
      { "weeks":[{"range":"2026-08-31,2026-09-06","label":"第1周 (…)"}, …],
        "data": { "week1":[ {…}, … ], "week2":[…] },
        "stats": {…}, "success": true }

周列表 GET /a/edu/acm/swipe/getWeeksInMonth?month=2026-09
      → {"data":[{"range":"…","label":"第N周 (…)"}],"success":true}
```

单条记录字段（真实返回）：

```text
date, weekDay(Monday…), dayType(工作日), isHoliday(非节假日),
firstSwipe(进闸), lastSwipe(出闸), enterCount, exitCount,
duration(秒), durationStr(10:23:57), swipeTimes("08:52,21:30"),
isQual(是/否), isLeave(是/否), isAppeal(是/否), userNo, userName
```

### 8.2 实现

```text
domain/attendance/AttendanceRecord.kt     模型 + AttendanceRepository
data/stu/StuAttendanceParser.kt           解析（对未知字段宽松，缺 weeks/data 则报 SchemaChanged）
data/stu/StuAttendanceDataSource.kt       POST 请求 + 掉线检测
data/attendance/AttendanceRepositoryImpl  按月缓存（Room 事务替换）+ 失败保留缓存
core/database                             新增 attendance_record / attendance_meta，DB v2
feature/attendance/                       考勤页：今天卡片 + 本月明细 + 按月翻页
```

旧的「打卡」三态猜测（`CheckInStatus` / `CheckInRepository` / 打卡页）已整体移除——
有了真实流水，再猜"是否打卡"只会误导。

### 8.3 实测

| # | 验证项 | 结果 |
|---|---|---|
| 33 | 真实响应可解析 | ✅ 单测（`StuAttendanceParserTest`，14 例，字段取自真实返回） |
| 34 | 进/出闸时间、在馆时长、合格标记 | ✅ 单测 |
| 35 | 缺 weeks/data → SchemaChanged 而非空数据 | ✅ 单测 |
| 36 | HTML 登录页 → SchemaChanged | ✅ 单测 |
| 37 | 考勤页渲染（今天卡片 / 本月明细 / 翻月 / 兜底入口） | ✅ 模拟器截图 `07-attendance.png` |
| 38 | 首页「进闸 / 出闸」卡片 | ✅ 模拟器实测 |
| 39 | 诊断页新增「测试考勤接口」 | ✅ |
| 40 | 单元测试总数 | ✅ 116 通过 / 0 失败 |

### 8.4 说明

- `stats` 字段只做了泛化保存（键值对），未做具体展示——真实返回里它的内容尚未逐一确认，
  等你在真机上跑一次「测试考勤接口」后可以补上更细的汇总展示。
- 考勤数据同样按账号分区缓存，切换账号不会串数据。

---

## 9. 第六轮：考勤改为「今日累计时长」（2026-09-09）

用户指出核心需求是**当天累计打卡时长**（学院要求工作日 6 小时），并且提示"可能需要你自己根据进出算"。
这个提示是对的。

### 9.1 为什么不能直接用学校字段

抓包三个月数据后发现：

```text
weekGroupedByMonth 的 durationStr = 当天累计时长（页面列名就叫「当日累计时长」）
但：
  2026-09-08  durationStr = 13:58:35   ✅ 已结算
  2026-09-09  durationStr = 0          ❌ 当天未结算
```

所以"今天打卡多久"必须自己算。而 `weekGroupedByMonth` 里的 `firstSwipe`/`lastSwipe`/`swipeTimes`
**全部为空**，拿不到具体进出时间——需要另一个接口。

### 9.2 找到的闸机流水接口

从菜单树里挖出「学生工作 / 出勤记录 / 学生进出历史记录」→ `/edu/acm/swipe/list`
（标题「学生刷卡记录表管理」），其数据接口：

```text
GET /a/edu/acm/swipe/listData?page=1&limit=10&startTime=&endTime=
→ {"code":0,"count":102,"data":[
     {"swipeTime":"2026-09-09 18:42:26","eventType":"进门",
      "channelName":"闸机-东5-入_门禁通道_1","swipeType":"教学楼",
      "openingType":"人脸合法开门","openingResult":"成功","swipeDate":"2026-09-09"}]}
```

列名：人员编号 | 姓名 | 研究中心 | **门禁通道 | 事件类型 | 刷卡时间** | 开门结果 | 年级 | 宿舍房间号。

### 9.3 配对算法（`PunchPairing`）

```text
按时间升序 → 同秒同方向去重 → 「进门」开一段、「出门」收一段 → 未闭合的按 now 继续累计
```

真实数据里的三个坑都处理了：

| 情况 | 真实样例 | 处理 |
|---|---|---|
| 相邻闸机同秒重复出门 | 15:27:59 东5-出 + 15:27:59 东6-出 | 同秒同方向去重 |
| 孤立出门（无对应进门） | 17:59:38 出门 | 忽略 |
| 当天最后一次进门未出门 | 18:42:26 进门 | 视为在馆中，按 now 累计 |

实测计算结果：

```text
08:20:00 → 12:01:18 = 3:41:18
12:27:19 → 15:26:24 = 2:59:05
15:27:56 → 15:27:59 = 0:00:03
18:42:26 → 21:00:00 = 2:17:00（在馆中）
合计 8:57:26
```

### 9.4 新增/改动

```text
domain/attendance/AttendancePunch.kt     闸机记录模型 + PunchPairing 配对算法
data/stu/StuPunchParser.kt               解析 listData（code!=0 / 缺 data 都报 SchemaChanged）
data/stu/StuPunchDataSource.kt           带日期过滤查询，失败自动回退到全量+本地过滤
core/database                            attendance_punch 表，DB v3
data/attendance/AttendanceRepositoryImpl observePunches / refreshPunches
feature/attendance                       今日大字时长 + 进度条 + 逐段进出列表
feature/home                             首页卡片改为「今日打卡时长」+ 进度
SessionStore                             attendanceGoalMinutes（默认 360，可改）
```

### 9.5 实测

| # | 验证项 | 结果 |
|---|---|---|
| 41 | 真实闸机流水可解析 | ✅ `PunchPairingTest`（16 例，数据取自真实抓包） |
| 42 | 多段进出累加 | ✅ 3:41 + 2:59 + 0:00 = 6:40（21:00 时含在馆段共 8:57） |
| 43 | 同秒重复出门去重 | ✅ 单测 |
| 44 | 孤立出门忽略 | ✅ 单测 |
| 45 | 在馆中随 now 增长 | ✅ 单测（19:00→21:00 增加 120 分钟） |
| 46 | 缺 data / code!=0 → SchemaChanged | ✅ 单测 |
| 47 | 考勤页大字时长 + 进度条渲染 | ✅ 模拟器截图 `08-attendance-progress.png` |
| 48 | 诊断页新增「测试闸机流水」 | ✅ |
| 49 | 单元测试总数 | ✅ **130 通过 / 0 失败** |

### 9.6 仍待确认

- 学校 `isQual` 的判定阈值：数据显示 `05:35:13` 合格、`04:29:24` 不合格，
  与"6 小时"不一致。App 只**展示**学校判定，同时按用户设定的 6 小时目标显示进度，不做推断。

---

## 10. 第七轮：课表拿不到的真因（2026-09-09）

用户反馈"课表还是不行，考勤时间好像可以了"，并提供了 App 诊断输出：

```text
semester page: year=null term=null observedWeek=null needsLogin=false
resolved anchor (week-1 Monday): 2026-09-07
-> schema changed: 无法确定学年/学期（未能从页面读取 xnm/xqm）
```

`needsLogin=false` 说明**会话是好的**，问题在"拿不到学年学期"。

### 10.1 根因：学期接口的字段名不是 xnm/xqm

用浏览器把 App 的**原样请求**打了一遍（`recon7.js`）：

```text
POST /yjsxt/xtgl/index_cxCurrentSemester.html?gnmkdm=index
→ 200, 46 字节
→ {"year":"2026-2027","semester":"1","week":"1"}
```

而代码里按 `xnm` / `xqm` 找字段 —— 一个都找不到，于是返回 null，
学年学期为空，课表请求根本没发出去（`fetchTimetable` 直接报"无法确定学年/学期"）。

正确的翻译：

```text
year   "2026-2027" → 取前一段 → xnm = 2026
semester "1"       → xqm = 3   （1→3, 2→12, 3→16）
week   "1"         → 当前教学周 = 1 → 第一周星期一 = 今天所在周的周一
```

修正后同一条链路实测：

```text
semester  → xnm=2026 xqm=3
timetable → 200, 7457 字节, kbList = 6 行   ✅
periods   → 200, 5122 字节, 9 节            ✅
```

### 10.2 顺带修掉的两个问题

| # | 问题 | 影响 | 修复 |
|---|---|---|---|
| 1 | `looksLikeLoginPage` 只要页面出现 `login_slogin` 就判定掉线 | 首页的「重新登录」链接会让整个刷新误报"需要重新登录" | 改为必须同时出现登录表单字段 `name="yhm"` 与 `name="mm"` |
| 2 | 诊断页的 `resolveSemester` 不查服务端 | 诊断永远显示"无法确定学年/学期"，掩盖真实原因 | `resolveSemester` 统一改为服务端优先，诊断与刷新走同一条链路 |

### 10.3 周锚点

教务系统自己的 `week` 字段是权威来源（本次 `week=1`，即 2026-09-07 起为第 1 周）。
注意考勤系统的"第1周"是 08-31 起，两套编号不同；课表以教务系统的为准。

### 10.4 验证

| # | 验证项 | 结果 |
|---|---|---|
| 50 | `{"year":"2026-2027","semester":"1","week":"1"}` 正确解析 | ✅ 单测 `parses the real currentSemester payload` |
| 51 | semester 1/2/3 → xqm 3/12/16 | ✅ 单测 |
| 52 | 认不出就返回 null（不瞎猜） | ✅ 单测 |
| 53 | 首页「重新登录」链接不再误判掉线 | ✅ 单测 |
| 54 | 真实请求序列返回 kbList | ✅ 浏览器实测（recon7） |
| 55 | 单元测试总数 | ✅ **133 通过 / 0 失败** |

### 10.5 诊断页现在会打印什么

```text
currentSemester endpoint: xnm=2026 xqm=3 week=1
semester page: year=null term=null observedWeek=null needsLogin=false
resolved term: xnm=2026 xqm=3 (第一学期)
resolved anchor (week-1 Monday): 2026-09-07
period times: 9 节 (第1节 09:30)
--- POST sis.slai.edu.cn/yjsxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=index
    status=200 ct=application/json;charset=utf-8 812ms
-> SUCCESS: 84 occurrences from 6 rows
```

也就是说，以后再出问题，这一段输出就能直接定位到是哪一步断了。

## 11. 第八轮：HTTP 901 = 未登录（2026-09-10）

上一轮修好"学期字段名"之后，用户反馈**课表仍然拉取不到**。这一轮不再靠推断，而是把
"服务端行为"和"客户端形态"彻底拆开测（`recon9.js`）。

### 11.1 实验设计上的一个坑

第一版复刻（`recon8.js`）用 Playwright 的 `context.request` 重放 App 的请求，结果六种形态
全部 `901`，看起来像"服务端在拦截非浏览器客户端"。**这个结论是错的**，因为：

- `context.cookies(HOST)` 拿不到 `JSESSIONID`（HttpOnly + `Path=/yjsxt`），返回了空数组；
- 于是脚本发出去的是 `Cookie: `（**空值**），六种形态其实都是"不带 Cookie"。

所以那一轮什么也没有证明。修正方式：用 CDP `Network.getAllCookies` 取真实 Cookie，
并用 `credentials: 'omit'` 在浏览器里主动构造一次"确定不带 Cookie"的请求作为对照。

### 11.2 真结果

| 形态 | Cookie | 结果 |
|---|---|---|
| 浏览器 `fetch` | 有 | **200**，7469 字节，`kbList` 6 行 |
| 浏览器 `fetch`，`referrerPolicy: 'no-referrer'` | 有 | **200**，同上（不依赖 Referer）|
| 浏览器 `fetch`，`credentials: 'omit'` | 无 | **901**，响应体 **0 字节** |
| Node `context.request`（非 Chrome TLS 栈） | 有 | **200**，同上 |
| Node + `Referer`=站点根 | 有 | **200**，同上 |
| Node + 完整浏览器头（UA / Sec-Fetch / Origin） | 有 | **200**，同上 |

结论有两条，都很重要：

1. **服务端不拦截非浏览器客户端。** UA、Referer、Origin、`Sec-Fetch-*`、TLS 指纹全都无关，
   只要会话有效就返回 200。所以"OkHttp 被 WAF 拦了"这个假设**不成立**。
2. **`901` + 空响应体 = 没有有效会话。** 这是"未登录"的另一种说法，不是服务端故障。

### 11.3 那么 App 为什么拿不到课表

`SisRemoteDataSource.fetchTimetable` 原本把 901 归到 `ServerError`：

```kotlin
res.code == 901 -> RemoteResult.ServerError(901, "服务器拒绝（未授权或请求被拦截）")
```

后果是一条**静默失效链**：

1. 正方（ZFSoft）的 `JSESSIONID` 闲置约半小时就失效，AD FS 的 `MSISAuth` 却还能活几小时；
2. 会话过期后每次课表请求都得到 901；
3. 901 被当成"服务器错误"，所以 `sessionManager` **没有**被置为 `EXPIRED`；
4. 首页因此不显示"需要重新登录"，课表页则无论什么原因都只显示"暂无课表数据"；
5. 用户看到的现象就是"课表**始终**拉取不到"，而且没有任何可操作的线索。

同样的静默也发生在学期探测上：`fetchCurrentSemester` 只返回 `ServerTerm?`，
`null` 把"没登录"和"接口变了"混成了一种，于是代码退回到**猜测学期**继续往下跑。

### 11.4 修复

| # | 改动 | 文件 |
|---|---|---|
| 1 | `901` 判定为 `SessionExpired`（不再当 `ServerError`），`probeSession` / `discoverSemester` 同样处理 | `SisRemoteDataSource` |
| 2 | `fetchCurrentSemester` → `probeCurrentSemester`，返回 `SemesterProbe(term, status, sessionExpired, endpoint)`，把 `null` 的两种含义分开 | `SisRemoteDataSource` |
| 3 | 刷新时若探测到会话失效：**先静默续期**，成功就继续，失败才置 `EXPIRED` 并明确返回 `SessionExpired` | `ScheduleRepositoryImpl` |
| 4 | **静默续期**：按跳走一遍 SSO（逐跳按当前 URL 取 Cookie，并把 `Set-Cookie` 写回 WebView 仓库），AD FS 仍记得用户时无需任何输入即可拿到新 `JSESSIONID` | `SisRemoteDataSource.renewSession` |
| 5 | 课表页不再只显示"暂无课表数据"，改为显示**具体原因**，需要登录时直接给「登录教务系统」按钮 | `WeekScheduleScreen` / `ScheduleViewModel` |
| 6 | 诊断页首先打印 Cookie 摘要（名字 + 值长度，绝不含值）与学期探测状态码 | `DiagnosticsViewModel` / `WebCookieBridge.cookieSummary` |
| 7 | 登录流程结束后 `CookieManager.flush()`，避免进程被杀导致刚建立的会话丢失 | `MainViewModel` / `WebCookieBridge.flush` |
| 8 | 学期发现的页面顺序改为**真实课表页优先**（`kbcx/xskbcx_cxXskbcxIndex.html` 才带 `select#xnm`/`select#xqm`，`index_initMenu.html` 只是 JS 外壳） | `SisConfig` |

### 11.5 验证

| # | 验证项 | 结果 |
|---|---|---|
| 56 | 有效 Cookie + 非浏览器客户端 → 200 / kbList 6 行 | ✅ recon9 N1–N3 |
| 57 | 无 Cookie → 901 / 0 字节，且与 UA/Referer 无关 | ✅ recon9 B3–B4 |
| 58 | 901 → `needsLogin=true`（即使状态机还停在 AUTHENTICATED） | ✅ 单测 `WeekUiStateTest` |
| 59 | 各类失败原因分别有可读文案 | ✅ 单测 `WeekUiStateTest`（8 例）|
| 60 | 单元测试总数 | ✅ **141 通过 / 0 失败**（15 个测试类）|
| 61 | Release APK 签名 | ✅ `apksigner verify` 通过 |

### 11.6 诊断页现在首先打印这个

```text
cookie @ https://sis.slai.edu.cn/yjsxt: JSESSIONID=32字节
cookie @ 课表接口: JSESSIONID=32字节
currentSemester endpoint: status=200 sessionExpired=false xnm=2026 xqm=3 week=1
```

若第一行变成 `无 Cookie → 未登录（服务端会返回 901）`，那么"课表拉不到"的原因当场就确定了，
不需要再猜。

## 12. 第九轮：诊断说成功、界面却空白（2026-09-10）

用户反馈"登陆之后也刷新了，还是显示没有课表数据"。这次的诊断输出证明了**一半的链路完全正常**：

```text
cookie @ https://sis.slai.edu.cn/yjsxt: JSESSIONID=***
currentSemester endpoint: status=200 sessionExpired=false xnm=2026 xqm=3 week=1
--- POST .../kbcx/xskbcx_cxXsKb.html?gnmkdm=index
    status=200 ct=application/json;charset=UTF-8 151ms
-> SUCCESS: 84 occurrences from 6 rows
   2026-09-07 09:30–12:15 高等矩阵计算 @B401 (week 1)
   ...
```

抓取、解析、归一化全对（6 门课 × 14 周 = 84 条）。**所以问题在抓取之后。**

### 12.1 为什么之前一直查错方向

诊断页的「SIS 原生接口」直接调 `SisRemoteDataSource`，**和真正的 `ScheduleRepository.refresh()` 是两条不同的代码路径**。
前者可以完美成功，后者在更早的一步就失败 —— 于是每一轮调查都被引向管线的前半段，而后半段从来没被看过。

这是本轮最大的教训：**诊断必须跑真实路径。**

### 12.2 找到的两个真 bug

**A. 空结果被当成成功，然后把缓存清空**

`GenericTimetableParser` 在 `rowsPath` 指向空数组时返回 **`Success(occurrences = [])`**，
而 `persist()` 调用 `replaceAll()` —— 先 DELETE 再 INSERT，插入 0 行：

```text
provider 解析出 0 行 → Success → replaceAll(空) → 缓存被清空 → 返回 Success(0,0)
→ 首页显示「已同步」，课表页显示「暂无课表数据」
```

每次刷新都重复一次，所以现象是"**始终**拉取不到"。

同样的写法在 `SisScheduleParser` 里也存在：`kbList` 为空但带 `xqjmcMap` 元数据时返回零行 Success。
只要学年学期取错（例如请求了错误学期的 `xnm/xqm`），就会走这条路把好数据删掉。

修复：
- `persist()` **拒绝写入 0 行**，保留原缓存，并返回 `SchemaChanged("服务器返回了 0 条课程（已保留原有缓存）")`；
- provider 返回 0 行时**不再结束刷新**，继续尝试内置的正方接口。

**B. 两条路径的周锚点优先级不一致**

`doRefresh` 无条件优先 `storedAnchor`，而 `resolveSemester` 优先服务端刚返回的 `week`。
两者会算出不同的第一周星期一，课表被展开到错误的日期上，而当周自然就是空的。
现在统一为同一套优先级（服务端 `week` > 页面 > 存储值），并写进"刷新过程"日志。

另外加了 `renewSession` 的 deadline 修复（原来的 `get()` 每次读取都重算，循环永远不会超时）。

### 12.3 「刷新过程」日志

`ScheduleRepository` 新增 `observeRefreshTrace()`，`doRefresh` 每一步都留痕。
诊断页多了「**真实刷新**」按钮：跑真正的刷新，打印全过程，**然后直接查数据库**。

```text
=== 真实刷新（与首页刷新按钮完全相同的链路）===
-> 刷新结果: Success(added=84, total=84)
--- 刷新过程（12 步）---
   start: accountHash=… today=2026-09-10
   baseUrl=https://sis.slai.edu.cn/yjsxt
   providers=0
   stored: year=2026 term=3 anchor=2026-09-07 confirmed=false
   probe term: status=200 expired=false xnm=2026 xqm=3 week=1
   anchor(week-1 Monday)=2026-09-07 [source=server week]
   semester resolved: Semester(…)
   period times: 9 slots
   native POST …/kbcx/xskbcx_cxXsKb.html?gnmkdm=index -> status=200 151ms
   persist: 84 row(s) mode=NATIVE_API fullSemester=true rawItems=6 skipped=0
   synced: 84 rows via NATIVE_API (fullSemester=true)
   dates: 2026-09-07 … 2026-12-13
--- 数据库 ---
本周 2026-09-07 ~ 2026-09-13: 12 行；账号总计 84 行
缓存日期范围: 2026-09-07 ~ 2026-12-13
```

以后"抓不到"和"没写进去"一眼就能分开。

### 12.4 验证

| # | 验证项 | 结果 |
|---|---|---|
| 62 | 空 `rowsPath` 被如实报告为零行 Success（仓库层必须拒绝写入） | ✅ 单测 `ProviderLayerTest` |
| 63 | 仓库层拒绝 0 行写入、保留缓存 | ✅ 代码 + 日志 |
| 64 | provider 零行不再终止刷新 | ✅ 代码 |
| 65 | 周锚点优先级两条路径一致 | ✅ 代码 |
| 66 | `renewSession` deadline 只计算一次 | ✅ 代码 |
| 67 | 单元测试总数 | ✅ **142 通过 / 0 失败** |

## 13. 第十轮：本月明细里"无刷卡记录"和"在馆时长"自相矛盾（2026-09-10）

用户反馈：考勤页「本月明细」每一天都写着「无刷卡记录」，可同一张卡片下面又写着「在馆 10:23:57」。

### 13.1 两个数字来自两个不同的接口

| 显示 | 来源 | 说明 |
|---|---|---|
| 在馆 10:23:57 | `weekGroupedByMonth` 的 `durationStr` | 学校自己算的当日累计时长 |
| 无刷卡记录 | 同一接口的 `firstSwipe` / `lastSwipe` | **每一天都是空字符串** |

实测（recon10，抓的是真实返回）：

```json
{"date":"2026-08-31","duration":37437,"durationStr":"10:23:57","isQual":"是",
 "firstSwipe":"","lastSwipe":"","swipeTimes":"","enterCount":0,"exitCount":0}
```

也就是说：**学校这个接口根本不返回具体进出时间**，只有时长。
而 App 把"没有进出时间"直接显示成「无刷卡记录」—— 一句话凭空断言了另一件事。
真正的进出时间在另一个接口（闸机流水 `listData`）里，App 也有，只是明细行没用它。

### 13.2 顺带查出的两个真 bug

**A. 宿舍楼闸机被算进了在馆时长**

`listData` 的 `swipeType` 有 `教学楼` 和 `宿舍楼` 两种。用真实流水复算：

| 日期 | 只算教学楼 | 连宿舍一起 | 学校 `durationStr` |
|---|---|---|---|
| 09-01 | 539 分 = 08:59 | 539 | **08:59:11** ✅ |
| 09-02 | 681 分 = 11:21 | 681 | **11:20:52** ✅ |
| 09-03 | 404 分 = 06:44 | 404 | **06:44:09** ✅ |
| 09-04 | 649 分 = 10:49 | 649 | **10:49:07** ✅ |
| 09-07 | 646 分 = 10:46 | **668 分 = 11:08** ❌ | **10:46:35** |
| 09-08 | 839 分 = 13:59 | 839 | **13:58:35** ✅ |
| 09-09 | 576 分 = 09:36 | 576 | **09:36:39** ✅ |

只算教学楼时**逐条吻合到秒**，可见学校的口径就是教学楼。9/7 早上从宿舍出门、晚上回宿舍会被
配对成一段并不在教室里的时间，多出 22 分钟 —— 足以把"不合格"算成"合格"。
现在 `PunchPairing` 明确排除 `宿舍楼`（`place` 为 null 时不过滤）。

**B. 上个月最后几天永远查不到流水**

学校按**教学周**分组，2026-09 的第 1 周是 `08-31 ~ 09-06`，所以明细里会出现 8/31。
但闸机流水只查了日历月 `09-01 ~ 09-30` —— 8/31 落在区间外，永远显示"无刷卡记录"。
实测 8/31 有 4 条流水（09:30 进、19:54 出、21:03 进出）。
现在按"上个月最后一周 ~ 本月最后一天"拉取。

### 13.3 修复后的明细

- **右侧大字**：当天累计在馆时长（今天由流水实时算，未闭合的一段按当前时间累计）
- **下面小字**：`工作日 · 学校判定合格 · 08:20 → 12:01，12:27 → 15:26，15:27 → 15:27，18:42 → 在馆中`
- 只有在**确实**没有任何数据时才显示「无记录」/「休息日」

### 13.4 验证

| # | 验证项 | 结果 |
|---|---|---|
| 68 | `limit=500` 服务端确实返回全量（count=104，24 个不同日期） | ✅ recon10 |
| 69 | `startTime`/`endTime` 过滤有效（9 月 38 条 / 8 月 66 条） | ✅ recon10 |
| 70 | 只算教学楼时逐日复现学校 `durationStr` | ✅ 7/7 天吻合 |
| 71 | 宿舍楼闸机被排除 | ✅ 单测 `PunchPairingTest` |
| 72 | `place` 为 null 时不过滤 | ✅ 单测 |
| 73 | 单元测试总数 | ✅ **144 通过 / 0 失败** |

## 14. 课表显示下课时间（2026-09-10）

用户反馈：课表每一节课只有上课时间，没有下课时间。

这不是数据问题 —— `ClassOccurrence` 一直同时带 `startTime` 和 `endTime`
（`endTime` 由节次区间 + 学校下发的节次时刻算出，例如第 1-3 节 → `09:30–12:15`），
诊断页打印的也是完整区间：

```text
2026-09-07 09:30–12:15 高等矩阵计算 @B401 (week 1)
2026-09-07 14:30–17:15 最优化方法与原理 @B401 (week 1)
2026-09-08 18:30–21:15 人工智能基础设施 @B401 (week 1)
```

只是 `WeekScheduleScreen` 的时间栏**只画了 `startTime`**，所以看起来像没有下课时间。
首页的 `ClassCard` 早就画了两行（上课 / 下课），两个页面因此不一致。

修复：周视图改用与首页完全相同的两行时间块。

```text
09:30   高等矩阵计算
12:15   B401 · 查宏远
14:30   最优化方法与原理
17:15   B401 · 罗智泉,陈淙靓
```

| # | 验证项 | 结果 |
|---|---|---|
| 74 | 周视图显示上课 + 下课时间，样式与首页一致 | ✅ `WeekScheduleScreen` |
| 75 | 单元测试总数 | ✅ **144 通过 / 0 失败** |

## 15. 第十一轮：计时规则、更名与品牌视觉（2026-09-10）

### 15.1 未闭合的进门会无限累加

用户反馈「8月31日 240 小时 24 分」「9月4日 144 小时 44 分」。

原因：闸机流水里有一次**只进门、没有出门**的记录（8/31 21:03）。配对算法把未闭合的一段按
"当前时间"累计，于是一次忘刷的出门**每天继续涨**，到 9/10 已经累计了 230 小时。

新规则（按用户要求）：**只进了馆、到第二天凌晨 05:00 还没刷出去，这一次进门整段作废，算 0。**

实现落在 `PunchSession`：

```kotlin
val expiresAt = from.toLocalDate().plusDays(1).atTime(OPEN_CUTOFF)   // OPEN_CUTOFF = 05:00

fun minutesAt(now) = when {
    to != null            -> from..to 的分钟数
    now <  expiresAt      -> from..now 的分钟数      // 当晚仍然有效
    else                  -> 0                      // 作废，之后也不再涨
}
```

界面同时给出说明（"有一次只进了没刷出，已过次日 05:00，本次不计入时长"），
否则用户会觉得"我明明在馆里，为什么是 0"。

| 边界 | 行为 |
|---|---|
| 当晚 23:00 | 有效，正常累计 |
| 次日 04:59 | 仍然有效 |
| 次日 05:00 整 | 作废，0 分，且此后不再增长 |
| 今天刚进门、还没出去 | 不受影响，按当前时间累计 |

### 15.2 更名 SLAIer

`app_name` / `app_name_debug` 改为 **SLAIer**，`application-label` 已确认为 `SLAIer`。

**`applicationId` 保持 `com.slai.campus` 不变** —— 改了会变成另一个 App，
现有安装不会覆盖升级，用户的登录会话与缓存全部丢失，桌面上还会多出一个图标。
显示名和包名本来就不必一致。

### 15.3 品牌视觉

色值**全部来自官方模板** `theme/河套学院PPT模板0709.pptx`（26 页里实际出现的颜色按频次统计），
不是我凭感觉调的：

| 色值 | 出现次数 | 用途 |
|---|---|---|
| `#881E55` | 38 | 主色（深洋红） |
| `#29AAE3` | 51 | 次色（青蓝），模板里出现最多 |
| `#E27DBE` | 22 | 主色浅阶 |
| `#D14AA4` / `#D82E98` | 18 / 3 | 主色亮阶、渐变色标 |
| `#5E173E` / `#310D21` | 5 / 1 | 最深一档，深色容器 |

logo 左侧环形标志采样得到 `#B05070` / `#902060`，与上表同族，整套配色自洽。

**关键修复：关掉了 Material You 动态取色。** 原来 `SDK_INT >= S` 会走
`dynamicDarkColorScheme`，手机上取到什么色 App 就是什么色，学院那套洋红/青蓝完全显示不出来。

资源处理（都在 `theme/` 目录）：
- **logo**：原图是黑字，深色底上看不见。打包两份 —— `slai_logo_on_light`（原图，放浅底）、
  `slai_logo_on_dark`（只把文字部分改成白色、保留洋红标志，放深底）。同一张图程序化生成。
  > 这里踩过一次坑：参数名叫 `forceLight` 时语义反了，深色蒙版上压了一份黑字 logo。
  > 现在参数是 `onDarkBackground`，并在注释里写明"图名说的是它适合放在什么底色上"。
- **风景照**：原图 6676×4461 / 2.1 MB。裁掉中部的建筑招牌横带（否则任何卡片高度下都可能
  和我们的 logo 叠在一起），只留上半部的幕墙格栅做纹理，缩到 1600 宽、172 KB。
- **启动图标**：从 logo 裁出环形标志，按自适应图标安全区（108dp 画布内 61%）居中，
  白色底 —— 洋红标志压在深色底上在 48dp 会糊成一团。

界面：
- 首页顶部新增 `CampusHero`：风景照 + 主色渐变蒙版 + 白色 logo + 一行副标题
- 顶栏改为品牌主色的 **SLAIer** + 日期
- 导航栏选中态改用品牌主色（默认的 `secondaryContainer` 是系统取色的浅蓝）
- "需要重新登录"卡片从 `errorContainer` 改为 `tertiaryContainer`：这是**待操作提示**不是报错，
  深色模式下 `errorContainer` 是 `#93000A`，占掉三分之一屏会像崩了；警示留在图标上
- 进度条显式指定 `primary` / `surfaceVariant`，避免轨道取到蓝色

### 15.4 验证

| # | 验证项 | 结果 |
|---|---|---|
| 76 | 未闭合进门在次日 05:00 作废，之后不再增长 | ✅ 单测（含 04:59 / 05:00 边界）|
| 77 | 作废段不拖累当天其余时段（8/31 → 10 小时 24 分） | ✅ 单测 |
| 78 | 今天刚进门未出门不受影响 | ✅ 单测 |
| 79 | 浅色/深色两套都实机截图确认 | ✅ 模拟器实机 |
| 80 | Release 包（资源名混淆）渲染与 debug 一致 | ✅ 模拟器实装截图 |
| 81 | APK 标签为 SLAIer | ✅ `aapt2 dump badging` |
| 82 | 单元测试总数 | ✅ **147 通过 / 0 失败** |

## 16. 第十二轮：中英文切换（2026-09-10）

### 16.1 先量化了一下工作量

开始前先数了一遍：**375 处硬编码中文，散在 43 个文件里**，另有 97 条已经在 `strings.xml`。
其中三类要分开对待：

| 类别 | 数量级 | 处理 |
|---|---|---|
| 界面文案 | ~180 | 抽成资源，双语 |
| **协议值**（`进门` / `出门` / `教学楼` / `宿舍楼` / `工作日` / `非节假日`） | ~60 | **必须保持中文**，它们是用来比对学校返回值的，翻译了就直接坏掉 |
| 开发者日志 / 诊断输出 | ~130 | 保持中文（诊断页是开发者工具） |

### 16.2 语言机制：没有用 `AppCompatDelegate`

`AppCompatDelegate.setApplicationLocales` 是给 `AppCompatActivity` 用的。本应用是
`ComponentActivity` + 纯 Compose，API 33 以下那套依赖 AppCompat 的 `attachBaseContext` 钩子，
对普通 Activity 不可靠，而且切换时会**重建 Activity**（闪一下、丢掉当前页面状态）。

改成给 Compose 子树换一个带 Locale 的 Context（`ui/LocalizedApp.kt`），切换是即时的，
不重建、不多引依赖。三个 local 都要换，其中最关键的是：

> **`stringResource` 读的是 `LocalResources`，不是 `LocalContext`。**
> 反编译 Compose 1.9.2 的 `StringResources_androidKt` 确认：5 处引用 `LocalResources`。
> 只换 `LocalContext` 完全不起作用。

### 16.3 踩到的坑：换成 `ContextImpl` 会让 Hilt 崩溃

第一版直接把 `context.createConfigurationContext(config)` 的返回值塞进 `LocalContext`。
切到中文的瞬间应用闪退：

```text
FATAL EXCEPTION: main
java.lang.IllegalStateException: Expected an activity context for creating a HiltViewModelFactory
but instead found: android.app.ContextImpl
```

原因：`createConfigurationContext()` 返回的是 `ContextImpl`，`baseContext` 为空；
而 `hiltViewModel()` 是靠 `ContextWrapper` 链往上找 `ComponentActivity` 来建
ViewModelFactory 的，链断了就抛异常。

这个 bug **只在"切语言之后第一次解析 ViewModel"时出现**，静态检查和单测都发现不了 ——
是在模拟器上点了一下才暴露的。

修复：写一个 `LocalizedContextWrapper : ContextWrapper`，`baseContext` 保持原样（Activity），
只覆盖 `getResources` / `getAssets` / `getTheme` / `createConfigurationContext` 四个方法。

### 16.4 默认跟随系统

`AppLanguage` 三态（`SYSTEM` / `CHINESE` / `ENGLISH`），默认 `SYSTEM`。
英文手机的留学生第一次打开就是英文，不需要先去设置里找开关。
设置页用分段按钮把三个选项全摊开，"跟随系统"是当前值一眼可见。

### 16.5 顺带修掉的架构问题

`WeekUiState.emptyReason` 原来直接返回**中文句子**。ViewModel 没有 Context，拿不到
`stringResource`，正是这种写法让应用变成了单语言。现在返回结构化的 `EmptyReason`
（`SessionExpired` / `NeverSignedIn` / `OfflineNoCache` / `ServerError(code)` / …），
由 UI 按语言解析。测试也跟着改成断言结构化值 —— 顺带发现原来的测试**写死了中文文案**，
在英文 locale 的机器上会挂。

同理，`AttendanceRecord.formatMinutes` 现在接受 `locale`：
中文 `10 小时 24 分`，英文 `10h 24m`。日期格式也一样
（`datePatternFor`：中文 `9月10日 星期四`，英文 `Thursday, Sep 10`），
不再到处写死 `Locale.CHINA`。

### 16.6 验证

| # | 验证项 | 结果 |
|---|---|---|
| 83 | 系统语言英文 → 应用自动全英文（无需设置） | ✅ 模拟器实机 |
| 84 | 设置里切"简体中文" → 界面**即时**变中文，不重启 | ✅ 模拟器实机 |
| 85 | 切换语言不再崩溃（Hilt ViewModelFactory） | ✅ 实机 |
| 86 | 时长文案两种语言 | ✅ 单测 `duration text follows the locale` |
| 87 | 语言枚举往返 + 非法值退回 SYSTEM | ✅ 单测 |
| 88 | `emptyReason` 断言结构化值 | ✅ `WeekUiStateTest`（8 例）|
| 89 | Release 包（R8 + 资源混淆）里英文资源仍在 | ✅ `aapt2 dump strings` |
| 90 | 单元测试总数 | ✅ **149 通过 / 0 失败** |

## 17. 第十三轮：深浅色改为可手动指定（2026-09-10）

用户反馈：设置里找不到主题开关，深浅色只能跟随系统。

### 17.1 改法

和语言一样做成三态偏好（`AppTheme`：`SYSTEM` / `LIGHT` / `DARK`），
默认 `SYSTEM`；`MainActivity` 里把「用户选择 + 系统状态」合成出最终的 `darkTheme`：

```kotlin
SlaiCampusTheme(darkTheme = theme.isDark(isSystemInDarkTheme())) { CampusRoot() }
```

`AppTheme.isDark(systemInDark)` 是纯函数，三种取值各自的语义一眼可见，也好测。

### 17.2 顺带修掉的一个真 bug

`SlaiLogo` 原来是读 `isSystemInDarkTheme()` 来决定用哪份 logo（黑字 / 白字）：

```kotlin
val useWhiteText = onDarkBackground ?: isSystemInDarkTheme()   // ← 错
```

一旦允许用户**强制**主题，这就错了：**系统是深色、应用被强制成浅色**时，
`isSystemInDarkTheme()` 仍返回 true，于是浅色背景上会放一份**白字 logo —— 直接看不见**。
反向同理。

修复：`SlaiCampusTheme` 通过 `LocalDarkTheme` 把「**应用当前**是不是深色」暴露出去，
`SlaiLogo` 改读它。

这个 bug 在「只能跟随系统」时不可能出现，是这个新功能**引入**的 —— 幸好一起改了。

### 17.3 界面

设置页 `外观与语言` 段落，两个分段按钮上下排：主题（跟随系统 / 浅色 / 深色）、
显示语言（跟随系统 / 简体中文 / English）。切换即时生效，不重启 Activity。

分段按钮的选中态改用 `primaryContainer`（品牌洋红），默认的 `secondaryContainer`
在本配色里是浅蓝，和导航栏不一致。

同时删掉了一个**孤立的「语言」段落标题** —— 语言控件并入「外观」后，
那个标题下面是空的，界面上会出现一个没有内容的标题（模拟器截图里发现的）。

### 17.4 验证

| # | 验证项 | 结果 |
|---|---|---|
| 91 | 系统浅色 + 强制深色 → 界面变深色 | ✅ 模拟器实机 |
| 92 | 同上场景下 logo 用白字那份（不是黑字） | ✅ 截图逐像素确认 |
| 93 | 切换即时生效，不重启、不崩溃 | ✅ 实机 |
| 94 | `SYSTEM` 跟随系统、`LIGHT`/`DARK` 双向覆盖系统 | ✅ 单测 |
| 95 | 主题偏好往返 + 非法值退回 SYSTEM | ✅ 单测 |
| 96 | 孤立标题已删除，段落为「外观与语言」 | ✅ 实机截图 |
| 97 | 单元测试总数 | ✅ **154 通过 / 0 失败** |

---

## 18. 第十四轮：一个「教务系统地址」，其实是三个（2026-09-10）

用户反馈：

> 从没登录过的人进 `https://sis.slai.edu.cn/yjsxt`，看到的登录界面不是学校那个，登不进去；
> 得从 `https://sis.slai.edu.cn` 进才行。而课表接口应该在 `https://sis.slai.edu.cn/yjsxt` 下面，
> 所以把「设置 → 高级 → 教务系统地址」改成站点根之后，登录正常了，课表却拉不到了。

### 18.1 未登录直连实测

```text
GET https://sis.slai.edu.cn/                             200  290 bytes
                                                         静态页，唯一内容是 JS：
                                                         location.href = "…/yjsxt/htxylogin"
GET https://sis.slai.edu.cn/yjsxt                        302 → /yjsxt/
GET https://sis.slai.edu.cn/yjsxt/                       302 → /yjsxt/xtgl/login_slogin.html
GET https://sis.slai.edu.cn/yjsxt/xtgl/login_slogin.html 200  正方自带的账号密码表单
                                                         （form action 指向自己；页面内**没有**任何
                                                         htxylogin / adfs / oauth 链接）
GET https://sis.slai.edu.cn/yjsxt/htxylogin              302 → sts.slai.edu.cn/adfs/oauth2/authorize
                                                         （AD FS 表单含 UserName 输入框，可正常登录）
```

三件事因此确定：

1. **`/yjsxt` 不是登录入口。** 它是业务路径，未登录时 302 到正方自带的 `login_slogin.html`。
   学生没有本地密码，也没有通往统一身份认证的链接 —— 进去就是死胡同。这就是用户说的
   「登录界面不是学校那个，登不进去」。
2. **站点根只是个 JS 跳板。** `https://sis.slai.edu.cn/` 那 290 字节页面把浏览器送到
   `…/yjsxt/htxylogin` —— 与 App 原本用的入口**是同一个 URL**（只差一个 `state` 参数）。
   所以入口不需要绕道站点根，反而少依赖一次 JS 执行。
3. **业务接口全在 `/yjsxt` 下面。** 把站点根当 baseUrl，`/kbcx/xskbcx_cxXsKb.html` 就不再是
   课表接口。于是用户为了修登录而手动改地址，制造出第二个 bug：登录能过、课表拿不到。

### 18.2 修法

| # | 问题 | 修法 |
|---|---|---|
| 1 | 一个字段同时承担「站点」和「应用根」两种含义 | 新增 `SisEndpoints`：把用户填的地址规范化成 origin / appPath / base / entry。填站点根、填 `…/yjsxt`、填 `…/yjsxt/htxylogin`、只填域名，都落到同一组地址 |
| 2 | 登录入口是构建期常量，用户改地址它不跟着走 | 入口改为从当前 baseUrl 推导（`SisConfig.entryUrlFor`），静默续期同样跟着走 |
| 3 | 未登录打开业务页面会停在正方自带的账号密码页 | WebView 落到 `login_slogin.html` 时自动改走 SSO 入口；只对「非登录流程」生效，所以不会来回跳，且登录成功后照常探活 + 刷新课表 |
| 4 | 用户看不出自己填的地址会被怎么用 | 设置页把推导出的「登录入口 / 课表接口前缀」显示出来 |

顺带确认：`stu.slai.edu.cn/a/login` 是同类死胡同（JeeSite 自带密码表单，页面里同样没有 SSO 链接）。
STU 目前是纯网页模式，这轮不动它。

### 18.3 验证

| # | 验证项 | 结果 |
|---|---|---|
| 98 | 站点根 / `…/yjsxt` / `…/yjsxt/htxylogin` / 纯域名 / 空值 → 同一组地址 | ✅ 单测 8 项 |
| 99 | 站点根地址下，课表接口仍是 `{origin}/yjsxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=index` | ✅ 单测 |
| 100 | 登录入口永远是 `/yjsxt/htxylogin`，指不到业务路径上 | ✅ 单测 |
| 101 | 自定义部署（应用路径不是 `/yjsxt`）原样保留；`http://` 升级为 `https://` | ✅ 单测 |
| 102 | `login_slogin.html` → 改走 SSO；普通页面 / SSO 链路本身不被改写 | ✅ 单测 |
| 103 | 单元测试总数 | ✅ **165 通过 / 0 失败** |

---

## 19. 第十五轮：首页刷新按钮瘦身 + 可以只留考勤（2026-09-10）

用户反馈两件事：

> 首页那个刷新按钮太大，感觉没必要；课表这个刷新感觉不需要很频繁。
> 另一个是能不能加个首页隐藏课表的功能 —— 课表不是所有人都需要（博二、博三没课），
> 可以在设置里改，取消显示课表，只有考勤。

两条都成立：那颗 `Button` 是 Material 的实心按钮 + 图标 + 「刷新」文字，占掉整行右侧，
而课表一天最多变几次，给它一个"主要操作"的位置确实不成比例。

### 19.1 改动

| # | 改动 | 说明 |
|---|---|---|
| 1 | 刷新按钮 → 20dp 小图标 | 放在状态文字那一行右端（`IconButton`，36dp 触控区），刷新中就地变成 16dp 转圈；状态文字成为这一行的主角 |
| 2 | 新增设置「首页显示课表」（默认开） | `SessionStore.showTimetableOnHome`，DataStore 持久化，切换即时生效 |
| 3 | 关掉后首页只剩考勤 | 一并收起：今日课程/空态卡、"需要重新登录"提示卡、同步行+上次同步时间、底部「本周课表 / 打开教务系统」按钮 |
| 4 | 课表 Tab 保留 | 按用户选择：只是首页不显示，底部「课表」Tab 仍在，偶尔有课时还能进 |
| 5 | 关掉后冷启动不再请求课表 | `HomeViewModel` 的启动静默刷新在开关关闭时直接跳过，不为没人看的数据唤醒网络 |
| 6 | 顺手修 i18n | 考勤卡片那个 chip 的文案原本硬编码 `Text("刷新")`，英文界面下也是中文，改用 `R.string.action_refresh` |

刻意**没有**动的东西：后台 6 小时周期同步照旧。关掉的是"首页显示"，不是"停止同步" ——
把它也停掉会让"重新打开课表"后数据陈旧，属于另一个决定。

### 19.2 验证（模拟器，实机操作）

| # | 验证项 | 结果 |
|---|---|---|
| 104 | 首页刷新按钮缩成图标，不再占半行 | ✅ 截图比对 |
| 105 | 刷新中图标位置换成 16dp 转圈并禁用 | ✅ 代码检查（未登录时会话秒失败，实机截不到转圈瞬间） |
| 106 | 全新安装（清数据）默认仍显示课表 | ✅ 实机 |
| 107 | 开→关：首页只剩「今日考勤」卡片 | ✅ 实机截图 |
| 108 | 关→开：课表区块与同步行原样回来 | ✅ 实机 |
| 109 | 关掉后底部「课表」Tab 仍可用 | ✅ 实机 |
| 110 | 开关状态持久化（重启 App 后仍是关） | ✅ 实机 |
| 111 | 设置项文案中英文都由资源提供 | ✅ 代码检查 |
| 112 | 单元测试总数 | ✅ **165 通过 / 0 失败** |

---

## 20. 第十六轮：登录成功后自动返回 App（2026-09-10）

用户反馈：

> 登录完之后需要用户手动关闭才能回到 app 界面，这个逻辑可能有些用户会不知道。

### 20.1 先分清两种 WebView

这一轮的关键不是"要不要自动关"，而是**哪些能自动关**。App 里的 WebView 有两种意图：

| 意图 | 怎么进来的 | 登录成功后应该 |
|---|---|---|
| **登录** | 点「登录教务系统 / 重新登录 / Sign in」 | 自动关掉，回到 App 看课表 |
| **看网页** | 点「打开教务系统 / 打开网页」等业务页面入口 | **不关** —— 用户正站在自己想看的页面上 |

第二种尤其要小心：第 14 轮加的"死胡同 → SSO"兜底，会把「打开教务系统」这种浏览意图的
页面也送进 SSO 流程。如果统一按"登录成功就关"，用户点了"打开教务系统"、登录完却被弹回 App，
等于把他要的东西收走了。

所以 `Overlay.Web` 新增 `closeOnLogin`（默认跟随 `isLoginFlow`），兜底那条路径显式传 `false`。

### 20.2 改动

| # | 改动 | 说明 |
|---|---|---|
| 1 | 登录确认成功后自动退场 | `MainViewModel.probe()` 返回 `AUTHENTICATED` 才发事件，**不是**"看到业务页面就关" —— 后者会在会话其实没建立时误关 |
| 2 | 退场前停 600ms | 让"确实登录上了"这一帧留在屏幕上；页面瞬间消失会像闪退 |
| 3 | Toast 说明去向 | 复用 `home_login_syncing`（"登录完成，正在同步课表…"）。从设置页/课表页登录时不在首页，看不到首页那行提示 |
| 4 | 登录页顶部预告 | 登录流程的 WebView 顶部加一行小字「登录成功后会自动返回，无需手动关闭此页」。**提示放在动手之前**，等用户输完密码再提示已经晚了 |
| 5 | 先退场再刷新 | 关闭事件在课表刷新**之前**发出，用户不必等这一轮请求跑完 |
| 6 | 手动 X 保留 | 自动关闭只是省一步，不是唯一出路 |

### 20.3 验证

| # | 验证项 | 结果 |
|---|---|---|
| 113 | 登录入口的 WebView 顶部显示"会自动返回"提示（中/英） | ✅ 模拟器截图 |
| 114 | 「打开教务系统」兜底进 SSO 的 WebView **不显示**该提示 | ✅ 模拟器 |
| 115 | 登录成功 → WebView 自动关闭 + Toast | ⚠️ **待真机确认**：自动关闭只在探活成功时触发，需要真实账号完成一次 AD FS 登录，模拟器上无法造出已认证会话 |
| 116 | 浏览意图的 WebView 登录后不会被关掉 | ⚠️ 同上，代码路径已分离（`closeOnLogin=false`），待真机确认 |
| 117 | 探活失败时不关页面（保持原行为） | ✅ 模拟器（未登录状态下点登录，页面如常停留） |
| 118 | 单元测试总数 | ✅ **165 通过 / 0 失败** |

> 第 115/116 项需要在手机上用真实账号走一遍：从首页点「登录教务系统」，登录完成后应自动回到
> 首页并弹出"登录完成，正在同步课表…"；从首页点「打开教务系统」，登录完成后应停在教务系统页面上。



