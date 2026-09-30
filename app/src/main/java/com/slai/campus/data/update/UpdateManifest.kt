package com.slai.campus.data.update

import com.slai.campus.domain.update.AppRelease
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A manifest is generated from a public release, not maintained by hand. */
object UpdateManifest {
    const val URL = "https://raw.githubusercontent.com/wangjt23/SLAIer-APP/update/update.json"

    fun parse(body: String?): AppRelease? = runCatching {
        val root = Json.parseToJsonElement(body.orEmpty()) as? JsonObject ?: return null
        fun text(key: String) = root[key]?.jsonPrimitive?.contentOrNull
        val tag = text("tagName") ?: return null
        val version = text("versionName") ?: return null
        if (!Regex("v\\d+\\.\\d+\\.\\d+").matches(tag) || tag != "v$version") return null
        val code = root["versionCode"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return null
        val name = text("apkName")?.takeIf { it == "slaier-$version.apk" } ?: return null
        val size = root["apkSize"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return null
        val hash = text("sha256")?.takeIf { Regex("[a-fA-F0-9]{64}").matches(it) } ?: return null
        val url = text("apkUrl") ?: return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps || parsed.host != "github.com" || parsed.port != 443 ||
            parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
            parsed.query != null || parsed.fragment != null || parsed.pathSegments !=
            listOf("wangjt23", "SLAIer-APP", "releases", "download", tag, name)) return null
        AppRelease(tagName = tag, versionName = version, versionCode = code,
            notes = text("notes"), publishedAt = text("publishedAt"), apkUrl = url,
            apkName = name, apkSize = size, sha256 = hash.lowercase())
    }.getOrNull()
}
