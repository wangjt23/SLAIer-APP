# AI 辅助开发工具链记录

> 对应计划书 §4.2
> 最后更新：2026-09-09

---

## 1. 本机工具链版本

| 组件 | 版本 | 位置 |
|---|---|---|
| JDK | Temurin 21.0.12.1+1 (LTS) | `~/android-dev/tools/jdk-21.0.12.1+1/Contents/Home` |
| Gradle | 8.14.3 | wrapper（`gradle/wrapper/`） |
| Android SDK Build-Tools | 36.0.0（+35.0.0 由 AGP 自动拉取） | `~/android-dev/sdk/build-tools/` |
| Android Platform | android-36 | `~/android-dev/sdk/platforms/` |
| Android Gradle Plugin | 8.13.2 | `gradle/libs.versions.toml` |
| Kotlin | 2.2.21 | 同上 |
| KSP | 2.2.21-2.0.5 | 同上 |
| Compose BOM | 2025.09.01 | 同上 |
| Hilt | 2.57.2 | 同上 |

`local.properties` 指向本机 SDK，**不入库**。

---

## 2. 关于 Android 官方 Skills

计划书 §4.2 建议把 `https://github.com/android/skills` 纳入开发工具链。本次实现**没有安装**它，原因记录如下，便于以后复核：

1. **它需要 Android CLI**（`android skills add …`）。本环境未安装 Android CLI，而 Skills 的价值主要是给 Agent 补充平台知识；
2. 本次任务的核心不确定性不在"Android 该怎么写"，而在"学校系统实际怎么工作"——后者只能靠实机验证；
3. 计划书本身也写明：`android/skills` **不是 App 运行时依赖，也不能替代**真实的网络请求与实机验证。

**建议后续补充**（按优先级）：

| Skill | 何时装 | 用途 |
|---|---|---|
| `android-cli` | 一旦要在本机跑模拟器/真机自动化 | 创建/运行 App、UI 检查、Skills 管理 |
| `testing-setup` | 扩展测试矩阵时 | Unit / UI / Screenshot 测试策略 |
| `android-intent-security` | 发布前 | 审计 Intent / PendingIntent / 外部链接边界 |
| `edge-to-edge` | UI 完善阶段 | 全面屏/手势导航适配 |
| `r8-analyzer` | Release 阶段 | 检查 R8 / shrink 配置（本仓库已手工写 `proguard-rules.pro`） |
| Play Policy | 若要上架 | 政策审计 |

安装方式（供参考，未执行）：

```bash
android skills list
android skills add android-cli --project=.
```

---

## 3. 证据优先级（本项目实际遵守）

```text
学校系统真实行为（本次：未登录态实测 + 计划书抓包约定）
        >
AD FS / Android 官方协议与平台文档
        >
Android Official Skills
        >
模型已有知识
```

具体落地：

- **实测确定的部分**（可以直接依赖）：
  - SIS / STU 的 OAuth 入口、`client_id`、`redirect_uri`、掉线重定向形态、Cookie 名与 Path；
  - SIS 是正方 v5（`/yjsxt/xtgl/login_slogin.html`、`zftal-ui-v5-1.0.2`）；
  - STU 是 JeeSite（`jeesite.session.id`、`/a/login;JSESSIONID=…`）。
- **未实测的部分**（代码里全部标为"候选"，运行时验证）：
  - 正方课表 JSON 的确切字段集与取值 → `SisConfig.timetableApiCandidates` 多候选 + 严格校验；
  - 学期第一周日期 → 三级证据（用户确认 / 页面观测周次 / 学期估算）；
  - STU 打卡接口 → 默认 `WEB_ONLY`，只在探测到可判定响应时才升级。
- **必须实机验证的结论**：
  1. 课表原生接口是否真的可用（诊断页第 2 个按钮）；
  2. 打卡是否存在稳定只读接口（诊断页第 3 个按钮）；
  3. AD FS 是否允许 WebView 内完成登录（第 1 次登录即可确认）。

---

## 4. 开发闭环（本次实际执行）

```text
读取计划书
   ↓
未登录态侦察 sis / stu（curl 跟随重定向，记录 client_id、Cookie、掉线形态）
   ↓
搭建工具链（JDK 21 + Android SDK 36 + Gradle 8.14.3 + AGP 8.13.2）
   ↓
按计划书 §23 目录结构写代码（64 个 Kotlin 文件）
   ↓
Gradle 编译 → 修错误（Room 主键冲突、嵌套注释、扩展函数导入、类型推断）
   ↓
单元测试（71 个用例，覆盖周次/节次/解析/脱敏/账号哈希/打卡三态）
   ↓
Release 签名 → 产出 APK
   ↓
（需要真机）诊断页验证原生链路
```

**没有做到的**：本机没有 Android 模拟器/真机，所以：

- 未跑 UI 截图测试；
- 未实机验证登录后的原生接口；
- 未实机跑测试矩阵（计划书 §21）。

这些必须在真机上用 App 内的**开发者诊断**页完成，见 `README.md` §2。

---

## 5. Agent 约束（写代码时必须遵守）

1. 不保存、不代填、不记录任何密码；
2. 不调用 `handler.proceed()` 忽略 SSL 错误；
3. 不注册 `addJavascriptInterface`；
4. 不把 `*.slai.edu.cn` 的 Cookie 全量发给任意子域；
5. 解析失败**不得**等价于"空课表"；接口失败**不得**等价于"未打卡"；
6. 数据库替换必须"先完整解析校验，再事务替换"，禁止先删后取；
7. 后台任务遇到会话失效必须静默停止，**不得**弹登录界面；
8. 任何日志/诊断输出必须过 `Redactor`；
9. 真实 Cookie / Token / 学号 / HAR 一律不提交（`.gitignore` 已覆盖 `docs/*.local.md`、`*.har`）。

---

## 6. 复现构建

```bash
export JAVA_HOME=~/android-dev/tools/jdk-21.0.12.1+1/Contents/Home
export ANDROID_HOME=~/android-dev/sdk

./gradlew :app:assembleDebug      # 调试版
./gradlew :app:assembleRelease    # 发布版（无签名配置时回退 debug 签名）
./gradlew :app:testDebugUnitTest  # 单元测试
```
