package com.slai.campus.data.update

import com.slai.campus.domain.update.AppRelease
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 解析 GitHub `releases/latest` 的响应。
 *
 * 写成纯函数是有意的：**这份 payload 的形状我们控制不了**（GitHub 会加字段、会改 assets 顺序），
 * 所以它必须能在单测里用真实抓下来的 JSON 反复钉住，而不是只能靠连网试。
 *
 * 关键取舍：拿不到 APK 资产时仍然返回对象（`hasApk == false`），由上层决定"这次发布没法装"，
 * 而不是当成解析失败 —— 否则一个只发源码的 tag 会让 App 报"接口异常"。
 */
object ReleaseParser {

    /** GitHub 会把 APK 命名成 `slaier-1.1.0.apk`；校验和文件固定叫这个名字。 */
    private const val CHECKSUMS_NAME = "SHA256SUMS.txt"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String?): AppRelease? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return null
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null

        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (tag.isEmpty()) return null

        val assets = runCatching { root["assets"]?.jsonArray }.getOrNull().orEmpty()

        fun asset(namePredicate: (String) -> Boolean) = assets
            .mapNotNull { element -> runCatching { element.jsonObject }.getOrNull() }
            .firstOrNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                namePredicate(name)
            }

        val apk = asset { it.endsWith(".apk", ignoreCase = true) }
        val checksums = asset { it.equals(CHECKSUMS_NAME, ignoreCase = true) }

        return AppRelease(
            tagName = tag,
            versionName = tag.removePrefix("v").removePrefix("V"),
            notes = root["body"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            publishedAt = root["published_at"]?.jsonPrimitive?.contentOrNull,
            apkUrl = apk?.get("browser_download_url")?.jsonPrimitive?.contentOrNull,
            apkName = apk?.get("name")?.jsonPrimitive?.contentOrNull,
            apkSize = apk?.get("size")?.jsonPrimitive?.longOrNull,
            checksumsUrl = checksums?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
        )
    }
}
