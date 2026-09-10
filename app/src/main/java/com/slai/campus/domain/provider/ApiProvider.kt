package com.slai.campus.domain.provider

import com.slai.campus.core.common.SchoolSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A declarative description of one school API call.
 *
 * The first version of this app hard-coded guessed endpoint paths, which turned out to be wrong: the
 * school's deployment answers `302 -> login` for *any* unknown path, so "it redirected" proved
 * nothing, and every guessed ZFSoft module path 404s. Rather than guess again, an endpoint is now
 * **data**: method, URL, body, and the mapping from the response's field names to this app's domain
 * model. A provider can be written by hand, pasted from a browser capture, or generated automatically
 * by the in-app capture tool.
 *
 * Nothing here is school-specific. The ZFSoft defaults exist only so that a freshly learned provider
 * needs no editing.
 */
@Serializable
data class ApiProvider(
    /** Stable id; generated from the URL when learned. */
    val id: String,
    /** Human label shown in Settings, e.g. "教务课表（学习）". */
    val name: String,
    /** [SchoolSystem.key]. */
    val system: String,
    /** [ProviderPurpose.key]. */
    val purpose: String,
    /** GET or POST. */
    val method: String = "GET",
    /** Absolute URL. May contain `{{xnm}}` / `{{xqm}}` placeholders. */
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** Raw request body for POST. May contain `{{xnm}}` / `{{xqm}}` placeholders. */
    val body: String? = null,
    val contentType: String? = null,
    /**
     * Where the array of rows lives in the JSON response, dot-separated
     * (`"kbList"`, `"data.list"`, `""` for a root array).
     */
    val rowsPath: String = "",
    /** Logical field name -> key in each row object. See [Fields]. */
    val fieldMap: Map<String, String> = emptyMap(),
    val note: String? = null,
    val enabled: Boolean = true,
    /** 0 means "written by hand". */
    val learnedAt: Long = 0L,
    val createdAt: Long = 0L
) {

    val isLearned: Boolean get() = learnedAt > 0

    val schoolSystem: SchoolSystem? get() = SchoolSystem.fromKey(system)

    val providerPurpose: ProviderPurpose? get() = ProviderPurpose.fromKey(purpose)

    /** Effective field map: explicit entries win, ZFSoft defaults fill the gaps. */
    fun resolvedFieldMap(): Map<String, String> = Fields.ZFSOFT_DEFAULTS + fieldMap

    fun keyFor(logical: String): String? = resolvedFieldMap()[logical]?.takeIf { it.isNotBlank() }

    /** Human-readable problems; empty means the provider is executable. */
    fun validate(): List<String> = buildList {
        if (id.isBlank()) add("id 不能为空")
        if (name.isBlank()) add("name 不能为空")
        if (SchoolSystem.fromKey(system) == null) add("system 必须是 sis 或 stu")
        if (ProviderPurpose.fromKey(purpose) == null) add("purpose 必须是 timetable 或 checkin")
        if (!method.equals("GET", true) && !method.equals("POST", true)) add("method 只能是 GET 或 POST")
        if (url.isBlank()) add("url 不能为空")
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            add("url 必须以 https:// 开头")
        }
        if (method.equals("POST", true) && body.isNullOrBlank()) add("POST 需要 body")
        if (purpose == ProviderPurpose.TIMETABLE.key && keyFor(Fields.COURSE_NAME) == null) {
            add("fieldMap 缺少课程名映射（${Fields.COURSE_NAME}）")
        }
    }

    val isValid: Boolean get() = validate().isEmpty()

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun fromJson(text: String): Result<ApiProvider> = runCatching { json.decodeFromString(serializer(), text) }

        fun toJson(provider: ApiProvider): String = json.encodeToString(serializer(), provider)
    }
}

/** Logical field names used by the generic parser. */
object Fields {
    const val COURSE_NAME = "courseName"
    const val TEACHER = "teacher"
    const val LOCATION = "location"
    const val CAMPUS = "campus"
    const val WEEKDAY = "weekday"
    const val PERIODS = "periods"
    const val WEEKS = "weeks"
    const val PARITY = "parity"
    const val COURSE_CODE = "courseCode"
    const val TEACHING_CLASS = "teachingClass"
    const val STUDENT_ID = "studentId"
    const val START_TIME = "startTime"
    const val END_TIME = "endTime"

    val ALL: List<String> = listOf(
        COURSE_NAME, TEACHER, LOCATION, CAMPUS, WEEKDAY, PERIODS, WEEKS, PARITY,
        COURSE_CODE, TEACHING_CLASS, STUDENT_ID, START_TIME, END_TIME
    )

    /**
     * ZFSoft v5 wire names. Applied when a provider does not override them, so a learned ZFSoft
     * provider works without any editing.
     */
    val ZFSOFT_DEFAULTS: Map<String, String> = mapOf(
        COURSE_NAME to "kcmc",
        TEACHER to "xm",
        LOCATION to "cdmc",
        CAMPUS to "xqmc",
        WEEKDAY to "xqj",
        PERIODS to "jcs",
        WEEKS to "zcd",
        PARITY to "sxbj",
        COURSE_CODE to "kch",
        TEACHING_CLASS to "jxbmc",
        STUDENT_ID to "xh"
    )

    /**
     * Alternative spellings seen across deployments and other vendors, used when guessing a mapping
     * from a captured payload.
     */
    val ALIASES: Map<String, List<String>> = mapOf(
        COURSE_NAME to listOf("kcmc", "courseName", "course_name", "coursename", "kcmc", "course", "title", "name"),
        TEACHER to listOf("xm", "teacher", "teacherName", "jsxm", "teacher_name", "js"),
        LOCATION to listOf("cdmc", "location", "classroom", "room", "jsjs", "place", "cdmc"),
        CAMPUS to listOf("xqmc", "campus", "xqmc", "area"),
        WEEKDAY to listOf("xqj", "weekday", "dayOfWeek", "xingqi", "week", "xq"),
        PERIODS to listOf("jcs", "jc", "periods", "period", "jieci", "jcor"),
        WEEKS to listOf("zcd", "weeks", "zhouci", "weekRange", "zc", "weekstr"),
        PARITY to listOf("sxbj", "parity", "oddEven"),
        COURSE_CODE to listOf("kch", "kch_id", "courseCode", "courseId", "kcdm", "code"),
        TEACHING_CLASS to listOf("jxbmc", "className", "teachingClass", "jxbmc", "classname"),
        STUDENT_ID to listOf("xh", "studentId", "studentNo", "xh_id", "sno"),
        START_TIME to listOf("startTime", "kssj", "start", "kssj", "starttime"),
        END_TIME to listOf("endTime", "jssj", "end", "jssj", "endtime")
    )
}

/** What a provider is for. */
enum class ProviderPurpose(val key: String, val label: String) {
    TIMETABLE("timetable", "课表"),
    CHECKIN("checkin", "打卡");

    companion object {
        fun fromKey(key: String?): ProviderPurpose? = entries.firstOrNull { it.key == key }
    }
}

/** Everything the provider UI needs to render a row. */
data class ProviderStatus(
    val provider: ApiProvider,
    val lastResult: String? = null,
    val lastOk: Boolean? = null,
    val rowCount: Int? = null
)
