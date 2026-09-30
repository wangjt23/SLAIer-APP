# 本机自动登录与静态更新清单

## 试用方法

在“设置 → 记住账号并自动登录”选择“保存并开启”，输入学校统一认证的账号密码。默认不会保存；账号、密码不进入实例状态、日志或备份。保存后会在考勤登录过期、App 处于前台且没有其他浮层时打开登录流程。登录成功并通过学生系统探活后，回到 App 刷新考勤。教务系统的手动登录入口也可以使用保存的同一套统一认证凭据。

自动填入只允许 `https://sts.slai.edu.cn` 的 `/adfs/ls`、`/adfs/ls/…`、`/adfs/oauth2/authorize` 页面及同域同路径范围的表单，默认 HTTPS 端口。不向 iframe、第三方页面、HTTP、非标准端口或抓包页面注入凭据；证书错误立即终止。

一次流程最多执行一次账号“下一页”和一次密码提交。密码提交前先持久化暂停标志；仅确认该自动流程成功后解除暂停，避免重启 App 导致重复尝试。验证码、二次验证、登录错误需手动处理。未确认成功时，可检查并重新保存凭据，或关闭再开启自动登录以明确授权重试。“清除账号密码”删除本机密文和 Keystore 密钥；“清除登录状态”会关闭自动登录，避免刚退出又重新登录。

使用 Android Keystore 的 AES-256-GCM 密钥加密账号、密码和自动登录状态，密文写入 `noBackupFilesDir/school-login.enc`。不要求每次解锁密钥时指纹确认，以支持前台自动登录。此能力不能改变学校服务器的会话有效期，也不能自动完成验证码或二次验证。

## 更新清单

新版优先读取 `https://raw.githubusercontent.com/wangjt23/SLAIer-APP/update/update.json`。使用 `update` 分支的 raw 直链，可避免另行配置 GitHub Pages；功能与静态文件方案相同。APK 仍来自 GitHub Releases。应用校验清单的版本、文件名、下载仓库及 SHA256，并校验下载 APK 的实际版本号和签名。Android 8 使用兼容的签名 API。

清单和 ETag 按来源一起缓存，304 后仍重新比较缓存版本，保留新版提示和忽略版本逻辑。没有可用缓存时不发送旧 ETag。自动检查每 24 小时最多发起一次，包括失败；手动检查可以立即重试。仅静态清单返回 404 时回退旧 Releases API，供尚未部署清单的试用阶段使用；静态清单部署前，匿名 API 限流仍可能发生。

`.github/workflows/update-manifest.yml` 在稳定 Release 正式公开后读取最新公开 Release，下载其正式 APK 和校验文件，并用 `aapt` 核对包名、版本号、versionCode、实际大小及 SHA256。`tools/build-update-manifest.py` 生成清单，工作流仅更新 `update` 分支。草稿或预发布不进入清单；现有标签构建仍生成草稿 Release。

正式上线时先将该工作流合入 main，再手动运行一次 “Update manifest” 工作流，以已有公开版本初始化 `update` 分支。以后公开稳定 Release 会自动更新。试用阶段不推送工作流、不建立远端分支、不部署清单。

发布 1.2.0 时，版本号调整为 1.2.0、versionCode 9；正式发布 Release 会触发清单初始化。手动运行工作流作为初始化或失败后重试的备用入口。

CI 的 Android SDK 步骤显式安装 `platform-tools`，避免旧版 setup-android 默认请求已移除的 `tools` 组件。标签触发构建失败时，可从 main 手动运行 Release 工作流，将 `release_tag` 填为已有标签（如 `v1.2.0`）；它使用现有标签的代码重新构建草稿，不移动标签。

## 验证边界

单元测试覆盖加密往返、随机 IV、篡改和错误密钥拒绝、可信页面判断、重复提交限制、实际学校两步表单结构的脚本执行、更新清单验证、304 缓存重新比较和缺失缓存恢复。生成器测试覆盖草稿/预发布拒绝、包版本、资产大小和 SHA256。学校登录表单结构通过未登录页面核对，不使用个人账号密码。实际账号重新登录和 GitHub 发布工作流的线上运行，仍需试用及正式上线后验证。

### 本次试用验证（2026-10-01）

- `:app:testDebugUnitTest`：256 项通过，0 失败、0 错误、0 跳过；清单生成器另有 5 项测试通过。
- `:app:assembleDebug`、`:app:assembleRelease`、`git diff --check` 通过。Release 包内为 `com.slai.campus`、1.1.4、versionCode 8，v2/v3 签名通过，证书与已有 `dist/slaier-1.1.4.apk` 一致。
- API 36 模拟器全程飞行模式，使用虚构账号验证设置入口、保存后的密文、重启后保存状态、关闭后重启保留关闭状态及清除后的文件删除；未向学校提交测试账号密码。
- APK 内中英文功能文案已核对。试用包为 `dist/slaier-1.1.4-auto-login-update.apk`，配套 `.apk.sha256` 校验通过；原正式包及其 `SHA256SUMS.txt` 校验仍通过。
- Lint：1 error、61 warnings。剩余错误是原有 `NotificationHelper.kt:108` 的通知权限提示，本次未修改该通知逻辑；任务成功不能视为 Lint 全部通过。
- 未执行暂存、提交、标签、推送、Release 发布或远端静态清单部署。
