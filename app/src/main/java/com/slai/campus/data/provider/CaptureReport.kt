package com.slai.campus.data.provider

import com.slai.campus.core.common.Redactor
import com.slai.campus.core.web.CaptureRecord
import com.slai.campus.core.web.InterceptedRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renders a capture session as a compact, redacted, copy-pasteable report.
 *
 * Purpose: when the automatic learner cannot recognise a payload, a human (or another agent) needs
 * enough structure to write the provider by hand. The report therefore shows, for every request the
 * page made: the method and path, the status, the request body, and the **shape** of the JSON —
 * top-level keys plus the keys of the first row of every array. Those key names are exactly what the
 * `fieldMap` needs, and they are not secret.
 *
 * Cookies, tokens, student numbers and passwords are masked by [Redactor] before anything is printed.
 */
object CaptureReport {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private const val BODY_PREVIEW = 600
    private const val MAX_RECORDS = 60

    fun build(
        records: List<CaptureRecord>,
        requests: List<InterceptedRequest> = emptyList(),
        extraNote: String? = null
    ): String = buildString {
        appendLine("# 校园 App 抓包报告")
        appendLine("# 已自动脱敏：Cookie / Token / 学号 / 密码")
        appendLine("# XHR 捕获 ${records.size} 个；页面请求 ${requests.size} 个")
        appendLine()

        extraNote?.let { appendLine("# 备注：$it"); appendLine() }

        if (requests.isNotEmpty()) {
            appendLine("## 页面发起的所有请求（含 iframe）")
            requests.map { "${it.method} ${safePath(it.url)}${if (it.mainFrame) "" else "  [iframe]"}" }
                .distinct()
                .forEach { appendLine("  $it") }
            appendLine()
        }

        if (records.isEmpty()) {
            appendLine("（主文档没有捕获到 XHR。如果上面列出的请求里有像课表的地址，请把那几行发我。）")
            return@buildString
        }

        records.take(MAX_RECORDS).forEachIndexed { index, record ->
            appendLine("## [$index] ${record.method} ${safePath(record.url)}")
            appendLine("status: ${record.status ?: "—"}")
            record.pageUrl?.takeIf { it.isNotBlank() }?.let {
                appendLine("page: ${safePath(it)}")
            }
            record.requestBody?.takeIf { it.isNotBlank() }?.let {
                appendLine("req: ${Redactor.redact(it.take(1000))}")
            }
            appendLine("json: ${describeJson(record.body)}")
            appendLine("body: ${Redactor.preview(record.body, BODY_PREVIEW)}")
            appendLine()
        }
    }

    /**
     * Host + path + query *names* only. Query values are dropped because a school URL can carry a
     * session ticket; the parameter names are what identify the endpoint.
     */
    private fun safePath(url: String?): String {
        if (url.isNullOrBlank()) return "—"
        val noScheme = url.substringAfter("://", url)
        val host = noScheme.substringBefore('/')
        val path = noScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val queryNames = noScheme.substringAfter('?', "")
            .split('&')
            .filter { it.isNotBlank() }
            .joinToString("&") { it.substringBefore('=') }
        val base = "$host/$path"
        return if (queryNames.isEmpty()) base else "$base?$queryNames="
    }

    /** A one-line description of the JSON shape, or a note that it is not JSON. */
    private fun describeJson(body: String): String {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return "not-json (${body.length} bytes)"
        }
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return "unparsable (${body.length} bytes)"

        val parts = mutableListOf<String>()
        when (root) {
            is JsonObject -> {
                parts += "top=${root.keys.take(20).joinToString(",")}"
                root.entries
                    .mapNotNull { (key, value) -> (value as? JsonArray)?.let { key to it } }
                    .sortedByDescending { it.second.size }
                    .take(3)
                    .forEach { (key, array) ->
                        val firstObject = array.filterIsInstance<JsonObject>().firstOrNull()
                        if (firstObject != null) {
                            parts += "array[$key] rows=${array.size} keys=${firstObject.keys.take(30).joinToString(",")}"
                        } else {
                            parts += "array[$key] rows=${array.size} (no objects)"
                        }
                    }
            }
            is JsonArray -> {
                val firstObject = root.filterIsInstance<JsonObject>().firstOrNull()
                parts += "root-array rows=${root.size}"
                firstObject?.let { parts += "keys=${it.keys.take(30).joinToString(",")}" }
            }
            else -> parts += "primitive"
        }
        return parts.joinToString(" | ")
    }

    /** Short label for the UI: how many requests, and how many look like JSON. */
    fun summary(records: List<CaptureRecord>, requests: List<InterceptedRequest> = emptyList()): String {
        val jsonCount = records.count { it.isJson }
        return "XHR ${records.size} 个（JSON $jsonCount 个）· 页面请求 ${requests.size} 个"
    }

    /** Every distinct host+path seen, useful to spot the timetable endpoint at a glance. */
    fun urlIndex(records: List<CaptureRecord>): List<String> =
        records.map { safePath(it.url) }.distinct()

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonPrimitive.orNull(): String? = content
}
