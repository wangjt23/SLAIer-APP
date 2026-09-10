# 发布指南：从本地仓库到 GitHub Release

面向第一次操作的人写，每一步都是可以直接复制的命令。
假设仓库目录是 `~/work/SLAI-Campus-APP`。

---

## 0. 动手前先确认三件事

```bash
cd ~/work/SLAI-Campus-APP

# ① APP签名密钥和密码没有被 git 跟踪
cat .gitignore | grep -E "keystore|\.jks"        # 应当有输出

# ② 仓库里没有 .git（还没初始化）
ls -d .git 2>/dev/null || echo "OK：还没有 git 仓库"

# ③ 构建和测试是绿的
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

> ### ⚠️ 一条铁律
>
> `keystore.properties` 里是APP**明文的签名密码**，`keystore/release.jks` 是**应用的签名身份**。
> 这两个文件已经在 `.gitignore` 里，正常 `git add .` 不会碰到它们。
>
> **但永远不要用 `git add -f` 强行加进去。** 一旦推到公开仓库：
>
> - 密钥泄露 = 任何人都能签一个"同名更新"推给你的用户；
> - 而且 **git 历史是删不干净的**，就算之后 `git rm`，旧提交里还在，只能整个仓库重建。
>
> 如果真的误传了：立刻换签名密钥（旧密钥作废）、重开仓库。
> 代价是所有用户必须卸载重装（签名不同的 APK 无法覆盖安装）。

---

## 1. 初始化本地仓库

```bash
cd ~/work/SLAI-Campus-APP

git init -b main
git add .
git status                     # ← 一定要看一眼这个列表
```

**`git status` 里绝对不应该出现：**

```text
keystore.properties
keystore/release.jks
local.properties
docs/endpoint-notes.local.md
dist/*.apk
theme/*.pptx
```

如果出现了任何一个，**停下来**，检查 `.gitignore`，`git rm --cached <文件>` 之后再来。

确认无误后提交：

```bash
git commit -m "Initial commit: SLAIer — 深河套学院研究生校园助手

- 课表：正方教务系统 v5，原生接口 + 四级降级
- 考勤：闸机流水配对、教学楼过滤、未刷出作废规则
- 双语：中文 / English，默认跟随系统
- 149 个单元测试，全部不依赖 Android 框架"
```

---

## 2. 在 GitHub 上建仓库

### 方式 A：网页（最简单）

1. 打开 [https://github.com/new](https://github.com/new)
2. **Repository name**：`SLAIer`（或 `slai-campus-app`）
3. **Description**：`深圳河套学院研究生校园助手 · 课表 / 考勤 / 上课提醒（非官方）`
4. **Public** / Private 按需选择
5. **不要**勾选 "Add a README file" / ".gitignore" / "license" —— 本地已经有了，
   勾了会产生冲突，还得先 `git pull --rebase`
6. 点 **Create repository**

创建后会看到一段"…or push an existing repository"的命令，就是下面第 3 步。

### 方式 B：`gh` CLI

```bash
brew install gh          # macOS
gh auth login            # 按提示在浏览器里授权

gh repo create SLAIer --public --source=. --remote=origin \
  --description "深圳河套学院研究生校园助手 · 课表 / 考勤 / 上课提醒（非官方）"
```

`gh` 会自动建仓库并配好 remote，可以直接跳到第 4 步。

---

## 3. 关联远程并推送

```bash
# 把 <你的用户名> 换成 GitHub 用户名
git remote add origin git@github.com:<你的用户名>/SLAIer.git
# 没有配 SSH 就用 HTTPS：
# git remote add origin https://github.com/<你的用户名>/SLAIer.git

git push -u origin main
```

用 HTTPS 的话，GitHub 现在**不接受账号密码**，需要 Personal Access Token：
Settings → Developer settings → Personal access tokens → Fine-grained tokens，
勾上 `Contents: Read and write`，然后把 token 当密码用。

推上去之后刷新页面，应该能看到 README 和截图。

---

## 4. 发一个版本（Release + APK）

### 4.0 先改版本号

`app/build.gradle.kts`：

```kotlin
versionCode = 1          // 每次发版必须 +1，否则用户装不上（"应用未安装"）
versionName = "1.0.0"    // 和 git tag 保持一致
```

```bash
git add app/build.gradle.kts
git commit -m "Bump version to 1.0.0"
git push
```

### 方式 A：本地构建 + 手动上传（推荐第一次这么做）

```bash
./gradlew :app:assembleRelease

cp app/build/outputs/apk/release/app-release.apk dist/slaier-1.0.0.apk
shasum -a 256 dist/slaier-1.0.0.apk | tee dist/SHA256SUMS.txt
```

顺手验证签名是对的：

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs dist/slaier-1.0.0.apk
```

然后：

1. GitHub 仓库页 → 右侧 **Releases** → **Draft a new release**
2. **Choose a tag** → 输入 `v1.0.0` → 点 "Create new tag: v1.0.0 on publish"
3. **Release title**：`SLAIer 1.0.0`
4. **Describe this release**：写这一版改了什么
5. 把 `slaier-1.0.0.apk` 和 `SHA256SUMS.txt` 拖进附件区
6. **Publish release**

> 为什么不把 APK 直接提交进 git：
> 它是 2.6 MB 的二进制，每次构建都变，提交进去会让仓库永久膨胀
> （git 每个历史版本都留着）。Releases 就是为放构建产物设计的，而且带下载统计。

### 方式 B：打 tag，让 CI 自动构建并发布

仓库里已经带了 `.github/workflows/release.yml`：推一个 `v*` 的 tag 就会
自动跑测试、构建、签名、算出 SHA256，然后生成一个 **草稿 Release**。

**先配置签名密钥**（只需一次）：

```bash
# 1. 把 keystore 转成 base64
base64 -i keystore/release.jks | pbcopy        # macOS，已复制到剪贴板
base64 -w0 keystore/release.jks                # Linux，手动复制输出
```

2. 打开 `https://github.com/<你的用户名>/SLAIer/settings/secrets/actions`
3. 依次 **New repository secret**，加四个：

| Name                  | Value                                                        |
| --------------------- | ------------------------------------------------------------ |
| `KEYSTORE_BASE64`   | 上一步的 base64 输出（一整行）                               |
| `KEYSTORE_PASSWORD` | `keystore.properties` 里的 `storePassword`               |
| `KEY_ALIAS`         | `keystore.properties` 里的 `keyAlias`（默认 `campus`） |
| `KEY_PASSWORD`      | `keystore.properties` 里的 `keyPassword`                 |

**然后打 tag：**

```bash
git tag v1.0.0
git push origin v1.0.0
```

到 **Actions** 标签页看构建进度（第一次大约 3–5 分钟）。
构建完在 **Releases** 里会有一个 **Draft**，检查一下 APK 和 SHA256SUMS，点 **Publish** 即可。

> 工作流里有两处是刻意加的：
>
> - 签名之后立刻 `rm -rf keystore keystore.properties`，**在任何上传步骤之前** ——
>   就算后面的步骤被改坏，密钥也不会进 artifact；
> - Release 默认是 **draft**，不自动公开，给你一个检查的机会。

> 公开仓库的 GitHub Actions 是免费的，不用担心额度。

---

## 5. 发布之后

用户看到的：

```text
Releases 页面 → 下载 slaier-1.0.0.apk → 手机上点开安装
```

他们可以用 `SHA256SUMS.txt` 校验文件没被篡改：

```bash
shasum -a 256 slaier-1.0.0.apk
```

**发下一个版本：**

```bash
# 1. 改 app/build.gradle.kts：versionCode +1，versionName 改掉
# 2. 提交
git add -A && git commit -m "Bump version to 1.0.1" && git push
# 3. 打 tag（方式 B）
git tag v1.0.1 && git push origin v1.0.1
```

> **`versionCode` 忘了加会怎样**：用户装的时候 Android 直接报「应用未安装」，
> 而且不会有任何解释。这是最容易踩的一个坑。

---

## 6. 如果搞砸了

| 情况                             | 处理                                                                                             |
| -------------------------------- | ------------------------------------------------------------------------------------------------ |
| 提交了不该提交的文件             | `git rm --cached <文件>`，确认 `.gitignore` 有它，重新提交。**注意：历史里还在**       |
| 已经推到 GitHub 才发现泄露了密码 | **立刻换密钥/改密码**。历史重写（`git filter-repo`）对已经公开的仓库基本没意义           |
| 推错了分支 / 想撤销一次 push     | `git revert <commit>` 再 push（不要 `--force` 到 main）                                      |
| Release 发错了                   | Releases 页面点**Edit** → Delete，或直接删掉那个 tag：`git push --delete origin v1.0.0` |

---

## 7. 关于签名密钥

> ### 先分清两种"密码"
>
> | 说法                   | 指的是                  | 在哪                                                                |
> | ---------------------- | ----------------------- | ------------------------------------------------------------------- |
> | **校园账号密码** | 你登录教务系统的那个    | 只进学校自己的页面。**本 App 从头到尾读不到、不保存、不代填** |
> | **签名密钥密码** | 给 APK 盖章用的私钥口令 | 在你电脑上的`keystore.properties` 里                              |
>
> 这一节讲的全是后者，和你的校园账号没有任何关系。

Android 要求每个 APK 都必须签名，而且**签名就是应用的身份**：系统判断"新包能不能覆盖安装"
看的是签名而不是包名。签名不同 → 报「应用未安装」，用户必须先卸载（缓存与登录状态全丢）。
所以要长期维护一个 App，就得一直用同一把密钥。

一份密钥由两个文件组成：

| 文件                     | 作用                                         |
| ------------------------ | -------------------------------------------- |
| `keystore/release.jks` | 私钥本身（保险箱）                           |
| `keystore.properties`  | 密钥在哪、口令是多少（写着位置和口令的纸条） |

构建时 Gradle 读 `keystore.properties` 打开 `.jks`，用它给 APK 盖章。
**两者任意一个缺失**，`app/build.gradle.kts` 会自动退回 debug 签名 ——
所以别人 clone 下来照样能编译，只是产物不能作为你正式版的更新。

自己生成一份：

```bash
cp keystore.properties.example keystore.properties   # 然后填自己设定的口令

keytool -genkeypair -v -keystore keystore/release.jks -alias campus \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -dname "CN=Your Name, OU=Personal, O=Personal, L=City, ST=Province, C=CN"
```

- `-alias` 必须和 `keystore.properties` 里的 `keyAlias` 一致；
- `-validity 10950` 是 10950 天（30 年）—— **密钥过期后就没法再更新这个 App 了**，别改小；
- `-dname` 只是自签证书上的署名，Android 不校验内容，写什么都不影响安全。

确认签名生效：

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs \
    app/build/outputs/apk/release/app-release.apk
```

### ⚠️ 这两个文件既不能泄露，也不能丢

- **泄露** —— 拿到 `.jks` + 口令的人，可以把恶意代码塞进同名 App、用同一把钥匙签名，
  你的用户手机上会**当成官方更新正常装上**，看不出任何区别。
  永远不要提交、不要贴群里、不要用 `git add -f` 绕过 `.gitignore`。
- **丢失** —— 你自己也无法再更新了，用户必须卸载重装，数据全部清零。

正确做法是**离线备份**，而且 `.jks` 和口令分开存（例如密钥放加密网盘、口令放密码管理器）。
只留在一台电脑上不算备份。

第 4 节「方式 B」讲的就是怎么把密钥交给 GitHub Actions，让 CI 替你签名发布 ——
那样密钥不进仓库，但流水线能用它盖章。
