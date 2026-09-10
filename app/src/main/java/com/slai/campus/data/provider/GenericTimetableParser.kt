package com.slai.campus.data.provider

import com.slai.campus.core.common.RemoteResult
import com.slai.campus.data.sis.SisKbItem
import com.slai.campus.data.sis.SisScheduleNormalizer
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
        source: ScheduleSource
    ): RemoteResult<SisTimetableParsed> {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return RemoteResult.SchemaChanged("响应为空")

        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return RemoteResult.SchemaChanged("JSON 无法解析（可能返回的是 HTML）")

        val rows = resolveRows(root, provider.rowsPath)
            ?: return RemoteResult.SchemaChanged(
                "找不到课程数组：rowsPath=\"${provider.rowsPath}\"，" +
                    "顶层字段=${(root as? JsonObject)?.keys?.take(8)?.joinToString(",") ?: "array"}"
            )

        if (rows.isEmpty()) {
            // An empty array from a *recognised* shape is a genuine empty timetable.
            return RemoteResult.Success(
                SisTimetableParsed(
                    occurrences = emptyList(),
                    response = SisTimetableResponse(kbList = emptyList()),
                    rawItemCount = 0,
                    skippedRows = 0,
                    studentId = null,
                    warning = null
                )
            )
        }

        val items = rows.mapNotNull { element -> (element as? JsonObject)?.let { toItem(it, provider) } }
        if (items.isEmpty()) {
            return RemoteResult.SchemaChanged("课程数组存在，但每一行都不是对象")
        }

        val response = SisTimetableResponse(kbList = items)
        val normalized = SisScheduleNormalizer.normalize(
            items = items,
            semester = semester,
            response = response,
            source = source,
            accountHash = accountHash
        )

        if (normalized.occurrences.isEmpty()) {
            return RemoteResult.SchemaChanged(
                "所有行都无法解析（${normalized.skipped}/${items.size} 行被跳过，" +
                    "学期锚点=${semester.firstWeekMonday ?: "未知"}）"
            )
        }

        val studentId = items.firstNotNullOfOrNull { it.studentId?.takeIf { v -> v.isNotBlank() } }

        return RemoteResult.Success(
            SisTimetableParsed(
                occurrences = normalized.occurrences,
                response = response,
                rawItemCount = items.size,
                skippedRows = normalized.skipped,
                studentId = studentId,
                warning = normalized.warning
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
