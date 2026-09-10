package com.slai.campus.data.stu

import com.slai.campus.core.common.RemoteResult
import com.slai.campus.domain.attendance.AttendancePunch
import com.slai.campus.domain.attendance.PunchDirection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 解析「学生刷卡记录表」的返回。
 *
 * Confirmed shape:
 * ```json
 * {"code":0,"count":102,"msg":"","data":[{"id":"…","swipeTime":"2026-09-09 18:42:26",
 *   "swipeDate":"2026-09-09","eventType":"进门","channelName":"闸机-东5-入_门禁通道_1",
 *   "swipeType":"教学楼","openingType":"人脸合法开门","openingResult":"成功"}]}
 * ```
 * 只要 `data` 不是数组就报 `SchemaChanged`，避免把"接口变了"当成"今天没刷卡"。
 */
object StuPunchParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private val TIME_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    )

    fun parse(body: String?): RemoteResult<List<AttendancePunch>> {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return RemoteResult.SchemaChanged("响应为空")
        if (text.startsWith("<")) return RemoteResult.SchemaChanged("响应是 HTML 而不是 JSON（可能被重定向到登录页）")

        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return RemoteResult.SchemaChanged("JSON 无法解析或根节点不是对象")

        val code = (root["code"] as? JsonPrimitive)?.contentOrNull
        if (code != null && code != "0") {
            return RemoteResult.SchemaChanged("服务端返回 code=$code ${(root["msg"] as? JsonPrimitive)?.contentOrNull ?: ""}")
        }

        val data = root["data"] as? JsonArray
            ?: return RemoteResult.SchemaChanged("缺少 data 数组（顶层字段：${root.keys.take(8).joinToString(",")}）")

        val punches = data.mapNotNull { element ->
            (element as? JsonObject)?.let(::toPunch)
        }

        // data 非空但一条都解析不出来 => 结构变了。
        if (data.isNotEmpty() && punches.isEmpty()) {
            return RemoteResult.SchemaChanged("data 有 ${data.size} 条，但没有一条能解析出 swipeTime")
        }
        return RemoteResult.Success(punches)
    }

    private fun toPunch(row: JsonObject): AttendancePunch? {
        val timeText = row.string("swipeTime") ?: return null
        val time = parseDateTime(timeText) ?: return null
        return AttendancePunch(
            id = row.string("id") ?: (timeText + "|" + (row.string("eventType") ?: "")),
            time = time,
            direction = PunchDirection.fromChinese(row.string("eventType")),
            channel = row.string("channelName"),
            place = row.string("swipeType"),
            openingType = row.string("openingType"),
            result = row.string("openingResult")
        )
    }

    fun parseDateTime(value: String?): LocalDateTime? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        TIME_FORMATS.forEach { format ->
            runCatching { LocalDateTime.parse(text, format) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
