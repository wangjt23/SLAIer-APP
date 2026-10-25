package com.slai.campus.data.sis

import com.slai.campus.domain.schedule.Semester
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The native and configured endpoints must agree on which account and term a payload belongs to. */
internal data class TimetableValidation(
    val studentId: String?,
    val reportedTerm: Pair<String, String>?,
    val error: String?
)

internal fun validateTimetable(
    root: JsonObject?,
    rowStudentIds: List<String?>,
    semester: Semester
): TimetableValidation {
    fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    val profile = root?.get("xsxx") as? JsonObject
    val year = profile?.text("XNM")
    val term = profile?.text("XQM")
    val reportedTerm = if (year != null && term != null) year to term else null
    val owners = (rowStudentIds + listOf(profile?.text("XH"), profile?.text("xh")))
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct()
    val code = root?.text("code")
    val error = when {
        (code != null && code != "0" && code != "200") ||
            (root?.get("success") as? JsonPrimitive)?.booleanOrNull == false ->
            "服务端返回课表错误，已保留原有缓存"
        reportedTerm != null && !semester.academicYear.isNullOrBlank() &&
            !semester.termCode.isNullOrBlank() && reportedTerm != (semester.academicYear to semester.termCode) ->
            "返回的课表学期与当前学期不一致，已保留原有缓存"
        owners.size > 1 -> "响应包含不同账号的课表，已保留缓存"
        else -> null
    }
    return TimetableValidation(owners.singleOrNull(), reportedTerm, error)
}
