package com.slai.campus.data.sis

import com.slai.campus.core.common.Redactor
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.data.sis.SisJson.string
import com.slai.campus.domain.schedule.ClassOccurrence
import com.slai.campus.domain.schedule.ScheduleSource
import com.slai.campus.domain.schedule.Semester
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Parses a ZFSoft timetable response, leniently but with hard validation of the fields we depend on.
 *
 * The rule from the design plan, verbatim: **"解析失败"绝不能等价于"今天没有课"**. So every failure mode
 * below returns a typed failure, never an empty list. An empty list is only ever produced by a
 * response that genuinely looks like a timetable with zero rows.
 */
object SisScheduleParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
        allowTrailingComma = true
    }

    private val HTML_MARKERS = listOf("<!doctype", "<html", "<head", "<body", "<script")

    fun parse(
        body: String?,
        semester: Semester,
        source: ScheduleSource,
        accountHash: String,
        periodTimes: List<Pair<java.time.LocalTime, java.time.LocalTime>>? = null
    ): RemoteResult<SisTimetableParsed> {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) {
            return RemoteResult.SchemaChanged("响应为空")
        }
        val lowered = text.take(512).lowercase()
        if (HTML_MARKERS.any { lowered.contains(it) }) {
            return RemoteResult.SchemaChanged("响应是 HTML 而不是 JSON（可能被重定向到登录页）")
        }

        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return RemoteResult.SchemaChanged("JSON 无法解析")
        val obj = root as? JsonObject
            ?: return RemoteResult.SchemaChanged("JSON 根节点不是对象")

        // The one field we cannot live without.
        if (!obj.containsKey("kbList")) {
            val keys = obj.keys.take(8).joinToString(", ")
            return RemoteResult.SchemaChanged("缺少 kbList 字段（顶层字段：$keys）")
        }

        val response = runCatching { json.decodeFromJsonElement(SisTimetableResponse.serializer(), obj) }
            .getOrElse { return RemoteResult.SchemaChanged("DTO 映射失败：${it.javaClass.simpleName}") }

        val items = response.kbList.orEmpty()

        // An empty kbList is only trusted when the payload still looks like a real timetable
        // response. Otherwise it is far more likely to be a changed/blocked endpoint.
        if (items.isEmpty() && response.xqjmcMap.isNullOrEmpty() && response.zsMap.isNullOrEmpty() &&
            response.sjkList.isNullOrEmpty()
        ) {
            return RemoteResult.SchemaChanged("kbList 为空且缺少课表元数据")
        }

        val studentId = items.firstNotNullOfOrNull { it.studentId?.trim()?.takeIf { v -> v.isNotEmpty() } }

        val normalized = SisScheduleNormalizer.normalize(
            items = items,
            semester = semester,
            response = response,
            source = source,
            accountHash = accountHash,
            periodTimes = periodTimes
        )

        // Rows existed but none could be expanded: that is a parsing failure, not an empty timetable.
        if (items.isNotEmpty() && normalized.occurrences.isEmpty()) {
            return RemoteResult.SchemaChanged(
                "所有课表行都无法解析（${normalized.skipped}/${items.size} 行被跳过，" +
                    "学期锚点=${semester.firstWeekMonday ?: "未知"}）"
            )
        }

        // The response reports the term it answered for; that is the most reliable source and lets
        // the app correct itself even if the stored codes were stale.
        val term = response.studentInfo?.let { info ->
            val year = info.string("XNM")
            val termCode = info.string("XQM")
            if (!year.isNullOrBlank() && !termCode.isNullOrBlank()) year to termCode else null
        }

        return RemoteResult.Success(
            SisTimetableParsed(
                occurrences = normalized.occurrences,
                response = response,
                rawItemCount = items.size,
                skippedRows = normalized.skipped,
                studentId = studentId,
                warning = normalized.warning,
                reportedTerm = term
            )
        )
    }

    /**
     * True when an HTML body is the ZFSoft **login screen** rather than a data page.
     *
     * This must be strict. The home page can legitimately contain a "重新登录" link pointing at
     * `login_slogin.html`, and a loose substring test turned that into a bogus "session expired".
     * The real login screen always carries the `yhm` (用户名) and `mm` (密码) form fields.
     */
    fun looksLikeLoginPage(body: String?): Boolean {
        val text = body?.lowercase().orEmpty()
        if (text.isBlank()) return false
        val hasLoginForm = text.contains("login_slogin") &&
            text.contains("name=\"yhm\"") &&
            text.contains("name=\"mm\"")
        val hasLoginTitle = text.contains("<title>") &&
            text.contains("教务管理系统") &&
            text.contains("login_slogin")
        return hasLoginForm || hasLoginTitle
    }

    /** True when a URL is the ZFSoft login page. */
    fun isLoginUrl(url: String?): Boolean {
        val value = url?.lowercase().orEmpty()
        return value.contains(SisConfig.LOGIN_PAGE_PATH.lowercase()) || value.contains("login_slogin")
    }

    fun preview(body: String?): String = Redactor.preview(body, 300)
}

data class SisTimetableParsed(
    val occurrences: List<ClassOccurrence>,
    val response: SisTimetableResponse,
    val rawItemCount: Int,
    val skippedRows: Int,
    val studentId: String?,
    val warning: String?,
    /** Academic year / term the server answered for, when it reports them (`xsxx.XNM/XQM`). */
    val reportedTerm: Pair<String, String>? = null
) {
    val isTrulyEmpty: Boolean get() = rawItemCount == 0
}
