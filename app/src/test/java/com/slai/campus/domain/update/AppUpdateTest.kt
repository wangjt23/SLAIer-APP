package com.slai.campus.domain.update

import com.google.common.truth.Truth.assertThat
import com.slai.campus.data.update.ReleaseParser
import org.junit.Test

/**
 * 应用内更新的纯逻辑。
 *
 * 这些判断的代价不对称：**漏报一次更新**用户只是晚一天看到新版，**误报**却会让他下载一个
 * 装不上的包（版本更旧、签名不符），最后收到一句"应用未安装"。所以每条边界都往"宁可漏报"的方向取。
 */
class AppVersionTest {

    @Test
    fun `parses the shapes a tag can take`() {
        assertThat(AppVersion.parse("v1.1.0")).containsExactly(1, 1, 0).inOrder()
        assertThat(AppVersion.parse("1.1.0-debug")).containsExactly(1, 1, 0).inOrder()
        assertThat(AppVersion.parse("2")).containsExactly(2).inOrder()
        assertThat(AppVersion.parse(" v1.2 ")).containsExactly(1, 2).inOrder()
        assertThat(AppVersion.parse("nightly")).isNull()
        assertThat(AppVersion.parse(null)).isNull()
    }

    @Test
    fun `compares numerically not lexicographically`() {
        // "1.10.0" 必须大于 "1.9.0" —— 字符串比较会得出相反结论。
        assertThat(AppVersion.isNewer("1.10.0", "1.9.0")).isTrue()
        assertThat(AppVersion.isNewer("1.9.0", "1.10.0")).isFalse()
    }

    @Test
    fun `treats missing segments as zero`() {
        assertThat(AppVersion.isNewer("1.2", "1.2.0")).isFalse()
        assertThat(AppVersion.isNewer("1.2.1", "1.2")).isTrue()
    }

    @Test
    fun `equal or older is never newer`() {
        assertThat(AppVersion.isNewer("1.1.0", "1.1.0")).isFalse()
        assertThat(AppVersion.isNewer("1.0.9", "1.1.0")).isFalse()
        // debug 后缀不影响比较：装了 1.1.0-debug 的开发机不会被提示升级到 1.1.0。
        assertThat(AppVersion.isNewer("1.1.0", "1.1.0-debug")).isFalse()
    }

    @Test
    fun `unparsable versions never trigger an update`() {
        assertThat(AppVersion.isNewer("nightly", "1.1.0")).isFalse()
        assertThat(AppVersion.isNewer("1.2.0", "unknown")).isFalse()
    }
}

class ReleaseDecisionTest {

    private fun release(version: String, apk: Boolean = true) = AppRelease(
        tagName = "v$version",
        versionName = version,
        apkUrl = if (apk) "https://example.com/slaier-$version.apk" else null
    )

    @Test
    fun `newer version is offered`() {
        val decision = ReleaseDecision.decide(release("1.2.0"), "1.1.0", ignoredVersion = null)
        assertThat(decision).isInstanceOf(UpdateDecision.Available::class.java)
    }

    @Test
    fun `same or older version is not offered`() {
        assertThat(ReleaseDecision.decide(release("1.1.0"), "1.1.0", null)).isEqualTo(UpdateDecision.UpToDate)
        assertThat(ReleaseDecision.decide(release("1.0.0"), "1.1.0", null)).isEqualTo(UpdateDecision.UpToDate)
    }

    @Test
    fun `ignored version is suppressed but a later one is not`() {
        val ignored = ReleaseDecision.decide(release("1.2.0"), "1.1.0", ignoredVersion = "1.2.0")
        assertThat(ignored).isInstanceOf(UpdateDecision.Ignored::class.java)

        // 忽略了 1.2.0 之后，1.2.1 仍然要提示。
        val next = ReleaseDecision.decide(release("1.2.1"), "1.1.0", ignoredVersion = "1.2.0")
        assertThat(next).isInstanceOf(UpdateDecision.Available::class.java)
    }

    @Test
    fun `a release without an apk never prompts`() {
        assertThat(ReleaseDecision.decide(release("1.2.0", apk = false), "1.1.0", null))
            .isEqualTo(UpdateDecision.UpToDate)
        assertThat(ReleaseDecision.decide(null, "1.1.0", null)).isEqualTo(UpdateDecision.UpToDate)
    }
}

class ChecksumFileTest {

    private val hash = "de85acb24634b4c81bf8c4b225cf3174ec8480231f50e7cf7fa2712ad1d92c23"

    @Test
    fun `reads gnu sha256sum output`() {
        val content = "$hash  slaier-1.1.0.apk\n"
        assertThat(ChecksumFile.sha256For("slaier-1.1.0.apk", content)).isEqualTo(hash)
    }

    @Test
    fun `tolerates binary marker, paths and case`() {
        assertThat(ChecksumFile.sha256For("slaier-1.1.0.apk", "$hash *slaier-1.1.0.apk"))
            .isEqualTo(hash)
        assertThat(ChecksumFile.sha256For("slaier-1.1.0.apk", "$hash  out/slaier-1.1.0.apk"))
            .isEqualTo(hash)
        assertThat(ChecksumFile.sha256For("slaier-1.1.0.apk", "${hash.uppercase()}  slaier-1.1.0.apk"))
            .isEqualTo(hash)
    }

    @Test
    fun `picks the right line when several assets are listed`() {
        val content = """
            aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  slaier-1.0.2.apk
            $hash  slaier-1.1.0.apk
        """.trimIndent()
        assertThat(ChecksumFile.sha256For("slaier-1.1.0.apk", content)).isEqualTo(hash)
    }

    @Test
    fun `returns null instead of guessing`() {
        assertThat(ChecksumFile.sha256For("slaier-1.1.0.apk", null)).isNull()
        assertThat(ChecksumFile.sha256For("slaier-1.1.0.apk", "")).isNull()
        assertThat(ChecksumFile.sha256For("slaier-1.1.0.apk", "not-a-hash  slaier-1.1.0.apk")).isNull()
        assertThat(ChecksumFile.sha256For("slaier-1.1.0.apk", "$hash  slaier-1.0.2.apk")).isNull()
    }
}

/**
 * 解析 GitHub `releases/latest` 的响应。
 *
 * 夹具是**真实抓下来的结构**（2026-09-10 的 release），不是编的 —— 之前抓包吃过亏：
 * 自己编的 payload 永远是"刚好合我心意"的那一种。
 */
class ReleaseParserTest {

    private val payload = """
        {
          "url": "https://api.github.com/repos/wangjt23/SLAIer-APP/releases/1",
          "html_url": "https://github.com/wangjt23/SLAIer-APP/releases/tag/v1.1.0",
          "id": 1,
          "tag_name": "v1.1.0",
          "target_commitish": "main",
          "name": "v1.1.0",
          "draft": false,
          "prerelease": false,
          "created_at": "2026-09-10T09:33:42Z",
          "published_at": "2026-09-10T09:33:42Z",
          "body": "## SLAIer 1.1.0\n\n考勤对齐学院口径。",
          "assets": [
            {
              "name": "SHA256SUMS.txt",
              "size": 83,
              "browser_download_url": "https://github.com/wangjt23/SLAIer-APP/releases/download/v1.1.0/SHA256SUMS.txt"
            },
            {
              "name": "slaier-1.1.0.apk",
              "size": 2708511,
              "browser_download_url": "https://github.com/wangjt23/SLAIer-APP/releases/download/v1.1.0/slaier-1.1.0.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses a real payload`() {
        val release = ReleaseParser.parse(payload)!!

        assertThat(release.tagName).isEqualTo("v1.1.0")
        assertThat(release.versionName).isEqualTo("1.1.0")
        assertThat(release.notes).contains("考勤对齐学院口径")
        assertThat(release.apkUrl).endsWith("/v1.1.0/slaier-1.1.0.apk")
        assertThat(release.apkName).isEqualTo("slaier-1.1.0.apk")
        assertThat(release.apkSize).isEqualTo(2708511)
        assertThat(release.checksumsUrl).endsWith("/v1.1.0/SHA256SUMS.txt")
        assertThat(release.hasApk).isTrue()
        assertThat(release.sizeText).isEqualTo("2.6 MB")
    }

    @Test
    fun `unknown fields do not break parsing`() {
        val withExtras = payload.replace("\"id\": 1,", "\"id\": 1, \"something_new\": {\"a\": [1,2,3]},")
        assertThat(ReleaseParser.parse(withExtras)?.versionName).isEqualTo("1.1.0")
    }

    @Test
    fun `a source-only release still parses but has no apk`() {
        // 只发了源码/校验和、没附 APK 的发布（GitHub 允许，tag 打歪了就会出现）。
        val sourceOnly = """
            {
              "tag_name": "v1.1.0",
              "body": "no apk here",
              "assets": [
                {"name": "SHA256SUMS.txt", "size": 83,
                 "browser_download_url": "https://github.com/wangjt23/SLAIer-APP/releases/download/v1.1.0/SHA256SUMS.txt"}
              ]
            }
        """.trimIndent()

        val release = ReleaseParser.parse(sourceOnly)
        assertThat(release).isNotNull()
        assertThat(release!!.hasApk).isFalse()
        // 没有 APK 的发布不该被提示安装。
        assertThat(ReleaseDecision.decide(release, "1.0.0", null)).isEqualTo(UpdateDecision.UpToDate)
    }

    @Test
    fun `garbage degrades to null instead of throwing`() {
        assertThat(ReleaseParser.parse(null)).isNull()
        assertThat(ReleaseParser.parse("")).isNull()
        assertThat(ReleaseParser.parse("not json")).isNull()
        assertThat(ReleaseParser.parse("""{"message":"Not Found"}""")).isNull()
    }
}

/** 更新说明的纯文本化：卡片里不渲染 Markdown，但也不能把 `##`、`**` 原样贴出来。 */
class ReleaseNotesTest {

    @Test
    fun `strips markdown noise`() {
        val notes = """
            ## SLAIer 1.1.0

            这一版把**考勤**重做了。

            ### 提示
            - 闸机记录有几分钟延时
            - 校外连不上时给提示
        """.trimIndent()

        val text = ReleaseNotes.plainText(notes)!!

        assertThat(text).doesNotContain("##")
        assertThat(text).doesNotContain("**")
        assertThat(text).contains("SLAIer 1.1.0")
        assertThat(text).contains("考勤")
        assertThat(text).contains("· 闸机记录有几分钟延时")
    }

    @Test
    fun `keeps links readable and drops rules`() {
        val text = ReleaseNotes.plainText("见 [下载页](https://example.com)\n\n---\n\n完")!!
        assertThat(text).contains("见 下载页")
        assertThat(text).doesNotContain("---")
        assertThat(text).doesNotContain("https://example.com")
    }

    @Test
    fun `truncates long notes with an ellipsis`() {
        val text = ReleaseNotes.plainText("字".repeat(500), limit = 100)!!
        assertThat(text.length).isEqualTo(101)
        assertThat(text).endsWith("…")
    }

    @Test
    fun `blank notes yield null so the card omits the block`() {
        assertThat(ReleaseNotes.plainText(null)).isNull()
        assertThat(ReleaseNotes.plainText("   ")).isNull()
        assertThat(ReleaseNotes.plainText("---")).isNull()
    }
}
