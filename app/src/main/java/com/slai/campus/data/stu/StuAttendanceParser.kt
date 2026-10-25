package com.slai.campus.data.stu

import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.session.AccountHasher
import com.slai.campus.domain.attendance.AttendanceMonth
import com.slai.campus.domain.attendance.AttendanceRecord
import com.slai.campus.domain.attendance.AttendanceStats
import com.slai.campus.domain.attendance.AttendanceWeek
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.LocalTime

/**
 * Parses the attendance ledger.
 *
 * Shape confirmed by a real capture (2026-09-09):
 * ```json
 * {
 *   "weeks": [{"range":"2026-08-31,2026-09-06","label":"第1周 (...)"}],
 *   "data": {"week1":[{"date":"2026-08-31","firstSwipe":"","lastSwipe":"",
 *                      "enterCount":0,"exitCount":0,"durationStr":"10:23:57",
 *                      "isQual":"是","isLeave":"否","isHoliday":"非节假日","dayType":"工作日"}]},
 *   "stats": {...},
 *   "success": true
 * }
 * ```
 *
 * Unknown fields are ignored. Every advertised week and every dated row must parse before the
 * month may replace its cache; missing data is never interpreted as an empty month.
 */
object StuAttendanceParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
    private val WEEK_KEY = Regex("week[1-9][0-9]*")

    fun parse(body: String?, month: String): RemoteResult<AttendanceMonth> {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return RemoteResult.SchemaChanged("响应为空")
        if (text.startsWith("<")) return RemoteResult.SchemaChanged("响应是 HTML 而不是 JSON（可能被重定向到登录页）")

        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return RemoteResult.SchemaChanged("JSON 无法解析或根节点不是对象")

        val success = (root["success"] as? JsonPrimitive)?.contentOrNull
        if (success == "false") {
            val message = (root["message"] as? JsonPrimitive)?.contentOrNull
            return RemoteResult.SchemaChanged("服务端返回失败${message?.let { "：$it" } ?: ""}")
        }

        val weeksArray = root["weeks"] as? JsonArray
        val dataObject = root["data"] as? JsonObject
        if ((root.containsKey("weeks") && weeksArray == null) || dataObject == null) {
            return RemoteResult.SchemaChanged("缺少 weeks / data 字段（顶层字段：${root.keys.take(8).joinToString(",")}）")
        }

        // Auxiliary data fields belong to neither the ledger nor its account identity.
        val weekData = dataObject.entries.filter { it.key.matches(WEEK_KEY) }
        if (weekData.isEmpty() && (weeksArray?.isEmpty() != true || success != "true")) {
            return RemoteResult.SchemaChanged("缺少有效的周考勤数组，无法确认月度记录完整")
        }

        if (!weeksArray.isNullOrEmpty()) {
            val advertisedKeys = (1..weeksArray.size).map { "week$it" }.toSet()
            if (advertisedKeys != weekData.map { it.key }.toSet()) {
                return RemoteResult.SchemaChanged("周考勤数据与周列表不一致，已保留原有缓存")
            }
        }

        val seenDates = mutableSetOf<LocalDate>()
        val owners = mutableSetOf<String>()
        val recordsByWeek = weekData.associate { (key, value) ->
            val rows = value as? JsonArray
                ?: return RemoteResult.SchemaChanged("周考勤数据不是数组，已保留原有缓存")
            key to rows.map { element ->
                val row = element as? JsonObject
                    ?: return RemoteResult.SchemaChanged("考勤记录不是对象，已保留原有缓存")
                val record = toRecord(row)
                    ?: return RemoteResult.SchemaChanged("考勤记录缺少有效日期，已保留原有缓存")
                if (!seenDates.add(record.date)) {
                    return RemoteResult.SchemaChanged("考勤记录包含重复日期，已保留原有缓存")
                }
                row.string("userNo")?.takeIf { it.length >= 3 && !it.contains('*') }?.let(owners::add)
                record
            }
        }

        // `weeks` gives the label + range; data-only deployments use their explicit week keys.
        val finalWeeks = if (!weeksArray.isNullOrEmpty()) {
            weeksArray.mapIndexed { index, element ->
                val obj = element as? JsonObject
                    ?: return RemoteResult.SchemaChanged("周考勤列表包含无效条目，已保留原有缓存")
                AttendanceWeek(
                    label = obj.string("label").orEmpty(),
                    range = obj.string("range").orEmpty(),
                    records = recordsByWeek.getValue("week${index + 1}")
                )
            }
        } else {
            recordsByWeek.entries.sortedBy { it.key.removePrefix("week").toIntOrNull() ?: Int.MAX_VALUE }
                .map { (key, records) -> AttendanceWeek(label = key, range = "", records = records) }
        }

        val stats = (root["stats"] as? JsonObject).orEmptyObject().mapValues { (_, v) -> primitiveText(v) }
        if (owners.size > 1) return RemoteResult.SchemaChanged("响应包含不同账号的考勤记录，已保留缓存")

        return RemoteResult.Success(
            AttendanceMonth(
                month = month,
                weeks = finalWeeks.filter { it.records.isNotEmpty() || it.label.isNotBlank() },
                stats = stats,
                summary = toSummary(stats),
                accountHash = owners.singleOrNull()?.let(AccountHasher::hash)
            )
        )
    }

    /** Maps the server's flat `stats` object onto the typed summary. */
    fun toSummary(stats: Map<String, String>): AttendanceStats {
        fun int(key: String): Int? = stats[key]?.toIntOrNull()
        fun bool(key: String): Boolean? = stats[key]?.let {
            it.equals("true", true) || it == "1" || it == "是"
        }
        val known = setOf(
            "totalWorkdays", "requiredPunches", "actualWorkdayPunches", "totalValidPunches",
            "maxAllowedRestdayPunches", "actualRestdayPunches", "rawRestdayPunches",
            "isMonthlyQualified", "qualificationMessage", "crossWeekUsedPunches"
        )
        return AttendanceStats(
            totalWorkdays = int("totalWorkdays"),
            requiredPunches = int("requiredPunches"),
            actualWorkdayPunches = int("actualWorkdayPunches"),
            totalValidPunches = int("totalValidPunches"),
            maxAllowedRestdayPunches = int("maxAllowedRestdayPunches"),
            restdayPunches = int("actualRestdayPunches") ?: int("rawRestdayPunches"),
            crossWeekUsedPunches = int("crossWeekUsedPunches"),
            monthlyQualified = bool("isMonthlyQualified"),
            qualificationMessage = stats["qualificationMessage"],
            extra = stats.filterKeys { it !in known }
        )
    }

    private fun toRecord(row: JsonObject): AttendanceRecord? {
        val date = row.string("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return AttendanceRecord(
            date = date,
            weekDay = row.string("weekDay"),
            dayType = row.string("dayType"),
            isHoliday = row.string("isHoliday"),
            firstSwipe = row.string("firstSwipe")?.let(::parseTime),
            lastSwipe = row.string("lastSwipe")?.let(::parseTime),
            enterCount = row.string("enterCount")?.toIntOrNull(),
            exitCount = row.string("exitCount")?.toIntOrNull(),
            durationText = row.string("durationStr"),
            durationMinutes = row.string("duration")?.toLongOrNull()?.let { (it / 60).toInt() },
            qualified = row.string("isQual")?.let { it == "是" || it.equals("true", true) || it == "1" },
            leave = row.string("isLeave")?.let { it == "是" || it.equals("true", true) || it == "1" },
            appeal = row.string("isAppeal")?.let { it == "是" || it.equals("true", true) || it == "1" },
            swipes = row.string("swipeTimes").orEmpty()
                .split(',', '，', ' ')
                .mapNotNull { parseTime(it.trim()) }
        )
    }

    /** `"08:52"`, `"08:52:31"`, `"2026-09-09 08:52"`. */
    fun parseTime(value: String?): LocalTime? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        val timePart = text.substringAfterLast(' ').trim()
        val parts = timePart.split(':')
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    private fun primitiveText(element: JsonElement): String = when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        else -> element.toString()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject?.orEmptyObject(): JsonObject = this ?: JsonObject(emptyMap())

}
