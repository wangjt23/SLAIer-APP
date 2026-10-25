package com.slai.campus.data.provider

import com.slai.campus.core.common.RemoteResult
import com.slai.campus.data.sis.SisKbItem
import com.slai.campus.data.sis.SisScheduleNormalizer
import com.slai.campus.data.sis.SisScheduleParser
import com.slai.campus.data.sis.validateTimetable
import com.slai.campus.data.sis.SisTimetableParsed
import com.slai.campus.data.sis.SisTimetableResponse
import com.slai.campus.domain.provider.ApiProvider
import com.slai.campus.domain.provider.Fields
import com.slai.campus.domain.schedule.ScheduleSource
import com.slai.campus.domain.schedule.Semester
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalTime

/**
 * Turns a provider's JSON response into dated [com.slai.campus.domain.schedule.ClassOccurrence]s.
 *
 * The mapping is entirely driven by `provider.fieldMap`, so the same code handles ZFSoft, a future
 * vendor, or a hand-written provider. Week/period expansion is delegated to the existing normalizer,
 * which already implements the odd/even-week and multi-range rules and is covered by unit tests.
 *
 * Validation follows the same rule as everywhere else: an unrecognisable payload is a typed failure,
 * never an empty timetable.
 */
object GenericTimetableParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun parse(
        body: String?,
        provider: ApiProvider,
        semester: Semester,
        accountHash: String,
        source: ScheduleSource,
        periodTimes: List<Pair<LocalTime, LocalTime>>? = null
    ): RemoteResult<SisTimetableParsed> {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return RemoteResult.SchemaChanged("响应为空")

        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return RemoteResult.SchemaChanged("JSON 无法解析（可能返回的是 HTML）")
        // Default or auto-learned ZFSoft mappings use all the native metadata and validation,
        // including profile-only identity. Explicit custom row keys retain the generic path.
        if (provider.rowsPath == "kbList" && root is JsonObject &&
            provider.resolvedFieldMap() == Fields.ZFSOFT_DEFAULTS) {
            return SisScheduleParser.parse(text, semester, source, accountHash, periodTimes)
        }

        val rows = resolveRows(root, provider.rowsPath)
            ?: return RemoteResult.SchemaChanged(
                "找不到课程数组：rowsPath=\"${provider.rowsPath}\"，" +
                    "顶层字段=${(root as? JsonObject)?.keys?.take(8)?.joinToString(",") ?: "array"}"
            )

        val items = rows.mapNotNull { element -> (element as? JsonObject)?.let { toItem(it, provider) } }
        val validation = validateTimetable(root as? JsonObject, items.map { it.studentId }, semester)
        validation.error?.let { return RemoteResult.SchemaChanged(it) }

        if (rows.isEmpty()) {
            // Learned ZFSoft providers can prove an empty semester through the same metadata
            // validation as the native endpoint. An arbitrary empty array cannot prove it.
            if (provider.rowsPath == "kbList" && root is JsonObject) {
                return SisScheduleParser.parse(text, semester, source, accountHash, periodTimes)
            }
            return RemoteResult.Success(
                SisTimetableParsed(
                    occurrences = emptyList(),
                    response = SisTimetableResponse(kbList = emptyList()),
                    rawItemCount = 0,
                    skippedRows = 0,
                    studentId = validation.studentId,
                    warning = null,
                    reportedTerm = validation.reportedTerm
                )
            )
        }

        if (items.isEmpty()) {
            return RemoteResult.SchemaChanged("课程数组存在，但每一行都不是对象")
        }

        val response = SisTimetableResponse(kbList = items)
        val normalized = SisScheduleNormalizer.normalize(
            items = items,
            semester = semester,
            response = response,
            source = source,
            accountHash = accountHash,
            periodTimes = periodTimes
        )

        if (normalized.occurrences.isEmpty()) {
            return RemoteResult.SchemaChanged(
                "所有行都无法解析（${normalized.skipped}/${items.size} 行被跳过，" +
                    "学期锚点=${semester.firstWeekMonday ?: "未知"}）"
            )
        }

        return RemoteResult.Success(
            SisTimetableParsed(
                occurrences = normalized.occurrences,
                response = response,
                rawItemCount = items.size,
                skippedRows = normalized.skipped,
                studentId = validation.studentId,
                warning = normalized.warning,
                reportedTerm = validation.reportedTerm
            )
        )
    }

    /** Navigates a dot-separated path to a JSON array. Empty path means "the root is the array". */
    fun resolveRows(root: JsonElement, rowsPath: String): JsonArray? {
        if (rowsPath.isBlank()) {
            return when (root) {
                is JsonArray -> root
                is JsonObject -> root.values.filterIsInstance<JsonArray>().maxByOrNull { it.size }
                else -> null
            }
        }
        var current: JsonElement = root
        for (segment in rowsPath.split('.').filter { it.isNotBlank() }) {
            current = when (current) {
                is JsonObject -> current[segment] ?: return null
                is JsonArray -> {
                    val index = segment.toIntOrNull() ?: return null
                    current.getOrNull(index) ?: return null
                }
                else -> return null
            }
        }
        return current as? JsonArray
    }

    private fun toItem(row: JsonObject, provider: ApiProvider): SisKbItem = SisKbItem(
        courseName = row.read(provider, Fields.COURSE_NAME),
        teacher = row.read(provider, Fields.TEACHER),
        location = row.read(provider, Fields.LOCATION),
        campus = row.read(provider, Fields.CAMPUS),
        weekday = row.read(provider, Fields.WEEKDAY),
        periods = row.read(provider, Fields.PERIODS),
        periodsRaw = row.read(provider, Fields.PERIODS),
        weekDescription = row.read(provider, Fields.WEEKS),
        parityFlag = row.read(provider, Fields.PARITY),
        courseCode = row.read(provider, Fields.COURSE_CODE),
        teachingClass = row.read(provider, Fields.TEACHING_CLASS),
        studentId = row.read(provider, Fields.STUDENT_ID)
    )

    /** Reads a mapped key, tolerating numbers and booleans by stringifying them. */
    private fun JsonObject.read(provider: ApiProvider, logical: String): String? {
        val key = provider.keyFor(logical) ?: return null
        val element = this[key] ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
