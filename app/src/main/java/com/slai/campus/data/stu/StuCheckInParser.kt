package com.slai.campus.data.stu

import com.slai.campus.core.common.Redactor
import com.slai.campus.core.common.RemoteResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The three-way answer the UI needs. Never collapses "unknown" into "not checked in". */
enum class StuCheckInAnswer { CHECKED_IN, NOT_CHECKED_IN, UNKNOWN }

/**
 * Classifies a check-in response.
 *
 * Strategy: structure first (JSON keys that plainly mean "checked"), then explicit text markers.
 * If the payload is ambiguous — both marker families present, or nothing recognisable — the answer is
 * [StuCheckInAnswer.UNKNOWN], because "接口失败 ≠ 未打卡" is a safety property, not a nicety.
 */
object StuCheckInParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val CHECKED_KEYS = setOf(
        "checked", "checkedin", "checked_in", "isChecked", "is_checked", "signed", "signin",
        "dakacg", "daka", "yidak", "yiDaKa", "hasChecked", "finished", "completed"
    )

    private val STATUS_KEYS = setOf("status", "state", "dakazt", "zt", "result", "code")

    fun classify(body: String?, contentType: String?): RemoteResult<StuCheckInAnswer> {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return RemoteResult.SchemaChanged("响应为空")

        val isJson = contentType?.contains("json") == true ||
            text.startsWith("{") || text.startsWith("[")

        if (isJson) {
            val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
                ?: return RemoteResult.SchemaChanged("JSON 无法解析")
            structuredAnswer(element)?.let { return RemoteResult.Success(it) }
        }

        return RemoteResult.Success(textAnswer(text))
    }

    private fun structuredAnswer(element: JsonElement): StuCheckInAnswer? {
        val primitives = mutableListOf<Pair<String, JsonPrimitive>>()
        collect(element, primitives)

        for ((key, value) in primitives) {
            val normalizedKey = key.lowercase().replace("_", "")
            if (CHECKED_KEYS.any { it.lowercase().replace("_", "") == normalizedKey }) {
                value.booleanOrNull?.let { return if (it) StuCheckInAnswer.CHECKED_IN else StuCheckInAnswer.NOT_CHECKED_IN }
                when (value.contentOrNull?.trim()?.lowercase()) {
                    "1", "true", "yes", "y", "已打卡", "已签到", "是" -> return StuCheckInAnswer.CHECKED_IN
                    "0", "false", "no", "n", "未打卡", "未签到", "否" -> return StuCheckInAnswer.NOT_CHECKED_IN
                }
            }
        }

        for ((key, value) in primitives) {
            if (STATUS_KEYS.any { it.lowercase() == key.lowercase() }) {
                when (value.contentOrNull?.trim()?.lowercase()) {
                    "1", "ok", "success", "done", "已完成", "已打卡" -> return StuCheckInAnswer.CHECKED_IN
                    "0", "fail", "none", "未打卡", "未完成" -> return StuCheckInAnswer.NOT_CHECKED_IN
                }
            }
        }
        return null
    }

    private fun collect(element: JsonElement, out: MutableList<Pair<String, JsonPrimitive>>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (value is JsonPrimitive) out += key to value else collect(value, out)
            }
            is JsonArray -> element.forEach { collect(it, out) }
            else -> Unit
        }
    }

    private fun textAnswer(text: String): StuCheckInAnswer {
        val lowered = text.lowercase()
        val checked = StuConfig.CHECKED_IN_MARKERS.any { lowered.contains(it.lowercase()) }
        val notChecked = StuConfig.NOT_CHECKED_IN_MARKERS.any { lowered.contains(it.lowercase()) }
        return when {
            checked && !notChecked -> StuCheckInAnswer.CHECKED_IN
            notChecked && !checked -> StuCheckInAnswer.NOT_CHECKED_IN
            // Ambiguous (e.g. a page that lists both states as legend text) or unrecognisable.
            else -> StuCheckInAnswer.UNKNOWN
        }
    }

    fun looksLikeLoginPage(body: String?): Boolean {
        val lowered = body?.lowercase().orEmpty()
        return lowered.contains("/a/login") || lowered.contains("jeesite") && lowered.contains("login")
    }

    fun isLoginUrl(url: String?): Boolean = url?.contains(StuConfig.LOGIN_PATH) == true

    fun preview(body: String?): String = Redactor.preview(body, 300)
}
