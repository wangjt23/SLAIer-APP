package com.slai.campus.data.stu

import com.slai.campus.core.common.RemoteResult
import com.slai.campus.domain.attendance.AttendancePeriod
import com.slai.campus.domain.attendance.AttendanceWeek
import com.slai.campus.domain.attendance.attendancePeriod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate

/** The school's complete week list, including weeks with no attendance rows yet. */
data class StuAttendancePeriodMetadata(
    val weeks: List<AttendanceWeek>,
    val period: AttendancePeriod?
)

object StuAttendancePeriodParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String?, month: String): RemoteResult<StuAttendancePeriodMetadata> {
        val root = runCatching { json.parseToJsonElement(body.orEmpty()) }.getOrNull() as? JsonObject
            ?: return RemoteResult.SchemaChanged("考勤周期响应不是有效 JSON")
        if ((root["success"] as? JsonPrimitive)?.contentOrNull != "true") {
            return RemoteResult.SchemaChanged("学校未确认考勤周期查询成功")
        }
        val data = root["data"] as? JsonArray
            ?: return RemoteResult.SchemaChanged("考勤周期缺少周日期范围")
        return parseWeeks(data, month)
    }

    fun fromWeeks(month: String, weeks: List<AttendanceWeek>): StuAttendancePeriodMetadata? {
        if (weeks.isEmpty()) return null
        val ranges = weeks.map { week ->
            val dates = week.range.split(',')
            if (dates.size != 2) return null
            val start = runCatching { LocalDate.parse(dates[0].trim()) }.getOrNull() ?: return null
            val end = runCatching { LocalDate.parse(dates[1].trim()) }.getOrNull() ?: return null
            start to end
        }
        val period = attendancePeriod(month, ranges) ?: return null
        return StuAttendancePeriodMetadata(weeks.map { it.copy(records = emptyList()) }, period)
    }

    /** JSON encoding keeps arbitrary server labels separate from delimiters and statistics. */
    fun encode(metadata: StuAttendancePeriodMetadata): String = JsonArray(metadata.weeks.map { week ->
        buildJsonObject { put("range", week.range); put("label", week.label) }
    }).toString()

    fun decode(text: String?, month: String): StuAttendancePeriodMetadata? {
        val weeks = runCatching { json.parseToJsonElement(text.orEmpty()) }.getOrNull() as? JsonArray ?: return null
        return (parseWeeks(weeks, month) as? RemoteResult.Success)?.data
    }

    private fun parseWeeks(array: JsonArray, month: String): RemoteResult<StuAttendancePeriodMetadata> {
        if (!StuAttendanceDataSource.isValidMonth(month)) return RemoteResult.SchemaChanged("考勤月份无效")
        if (array.isEmpty()) return RemoteResult.Success(StuAttendancePeriodMetadata(emptyList(), null))
        val weeks = array.map { element ->
            val obj = element as? JsonObject ?: return RemoteResult.SchemaChanged("考勤周列表包含无效条目")
            val range = (obj["range"] as? JsonPrimitive)?.contentOrNull
                ?: return RemoteResult.SchemaChanged("考勤周缺少日期范围")
            AttendanceWeek((obj["label"] as? JsonPrimitive)?.contentOrNull.orEmpty(), range, emptyList())
        }
        return fromWeeks(month, weeks)?.let { RemoteResult.Success(it) }
            ?: RemoteResult.SchemaChanged("考勤周日期范围不完整或不连续，已保留原有周期")
    }
}
