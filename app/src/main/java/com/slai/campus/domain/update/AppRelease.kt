package com.slai.campus.domain.update

/**
 * 一次发布，投影自 GitHub Releases API 的 `releases/latest`。
 *
 * 只用得上这几项：版本号（比较）、APK 资产（下载）、`SHA256SUMS.txt`（完整性）、
 * release body（更新说明）。其余字段一律不解析，免得 GitHub 改结构就崩。
 */
data class AppRelease(
    /** 原始 tag，例如 `v1.1.0`。 */
    val tagName: String,
    /** 去掉 `v` 前缀的版本号，用来和本地 `versionName` 比较。 */
    val versionName: String,
    /** 发布说明（Markdown 原文）。 */
    val notes: String? = null,
    val publishedAt: String? = null,
    /** APK 资产；为空说明这次发布没有可安装的包。 */
    val apkUrl: String? = null,
    val apkName: String? = null,
    val apkSize: Long? = null,
    /** `SHA256SUMS.txt` 资产；有它才做完整性校验。 */
    val checksumsUrl: String? = null
) {
    val hasApk: Boolean get() = !apkUrl.isNullOrBlank()

    /** 「1.1 MB」这类给用户看的体积文案。 */
    val sizeText: String?
        get() = apkSize?.takeIf { it > 0 }?.let { bytes ->
            val mb = bytes / 1024.0 / 1024.0
            if (mb >= 1) String.format(java.util.Locale.US, "%.1f MB", mb)
            else "${bytes / 1024} KB"
        }
}

/** 检查更新的结论。 */
sealed interface UpdateDecision {
    /** 本地已经是最新（或更新，例如自己编译的版本）。 */
    data object UpToDate : UpdateDecision

    data class Available(val release: AppRelease) : UpdateDecision

    /** 有新版，但用户对这个版本点过「忽略此版本」。 */
    data class Ignored(val release: AppRelease) : UpdateDecision
}

/**
 * 版本号比较。
 *
 * 只比较前导的数字段：`v1.2.3`、`1.2.3`、`1.1.0-debug`、`1.2` 都能解析；
 * 解析不出来（例如 tag 叫 `nightly`）就当作"不确定"，此时**不提示**更新 ——
 * 宁可漏报一次，也不要因为解析错误把用户骗去装一个更旧的包。
 */
object AppVersion {

    private val LEADING_NUMBERS = Regex("""^[vV]?(\d+(?:\.\d+)*)""")

    fun parse(raw: String?): List<Int>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = LEADING_NUMBERS.find(text) ?: return null
        return match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    }

    /** [candidate] 是否严格新于 [current]；任一无法解析时返回 false。 */
    fun isNewer(candidate: String?, current: String?): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}

/**
 * 把 release notes（Markdown）压成一段能在卡片里直接读的纯文本。
 *
 * 卡片里没做 Markdown 渲染 —— 引入一个渲染库只为了显示更新说明不划算，但直接把 `##`、`**`
 * 原样贴出来又很难看（实测第一次就是这么显示的）。所以只做最小的清理：去标题符号、
 * 去强调符号、列表换成 ·、多余空行压掉，然后截断。
 */
object ReleaseNotes {

    fun plainText(markdown: String?, limit: Int = 280): String? {
        val raw = markdown?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val cleaned = raw.lineSequence()
            .map { line ->
                line.trim()
                    // 标题可能有多级（`##`、`###`），必须整段前缀一起去掉。
                    .replace(Regex("^#{1,6}\\s*"), "")
                    .replace(Regex("^[-*+]\\s+"), "· ")
                    .replace("**", "")
                    .replace("__", "")
                    .replace(Regex("`([^`]*)`"), "$1")
                    .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            }
            .filter { it.isNotEmpty() && it != "---" && it != "***" }
            .joinToString("\n")

        if (cleaned.isEmpty()) return null
        return if (cleaned.length <= limit) cleaned else cleaned.take(limit).trimEnd() + "…"
    }
}

/** 从「发布 + 本地版本 + 已忽略版本」得出该不该提示。 */
object ReleaseDecision {

    fun decide(
        release: AppRelease?,
        currentVersionName: String?,
        ignoredVersion: String?
    ): UpdateDecision {
        if (release == null || !release.hasApk) return UpdateDecision.UpToDate
        if (!AppVersion.isNewer(release.versionName, currentVersionName)) return UpdateDecision.UpToDate
        val ignored = ignoredVersion?.trim().orEmpty()
        return if (ignored.isNotEmpty() && AppVersion.parse(ignored) == AppVersion.parse(release.versionName)) {
            UpdateDecision.Ignored(release)
        } else {
            UpdateDecision.Available(release)
        }
    }
}

/**
 * 解析 `sha256sum` 的输出。
 *
 * 两个细节都实测过：GNU 的默认格式是「哈希 + 两个空格 + 文件名」，二进制模式会在文件名前多一个 `*`；
 * 大小写也不统一。校验和文件名本身可能带路径，所以只取最后一段比较。
 */
object ChecksumFile {

    fun sha256For(fileName: String, content: String?): String? {
        val wanted = fileName.substringAfterLast('/').trim()
        if (wanted.isEmpty() || content.isNullOrBlank()) return null
        return content.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size < 2) return@mapNotNull null
                val hash = parts[0]
                val name = parts[1].trimStart('*').substringAfterLast('/').trim()
                if (hash.length != 64 || !hash.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return@mapNotNull null
                if (!name.equals(wanted, ignoreCase = true)) return@mapNotNull null
                hash.lowercase()
            }
            .firstOrNull()
    }
}
