package com.slai.campus.data.sis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Wire format of the ZFSoft v5 student timetable response.
 *
 * Two deliberate choices:
 *  1. **Everything is optional and lenient.** The school may add fields; that must not break us.
 *     `ignoreUnknownKeys` is on and unknown keys are simply dropped.
 *  2. **Presence is checked explicitly, not by type.** `SisScheduleParser` looks at the raw
 *     [JsonObject] for the keys it depends on, so a missing `kbList` becomes `SchemaChanged`
 *     instead of a silently empty timetable.
 */
@Serializable
data class SisTimetableResponse(
    /** The list of class sessions. The single field we truly cannot do without. */
    @SerialName("kbList") val kbList: List<SisKbItem>? = null,

    /** Period definitions: `{"sjk":"1","sjmc":"第一节","sj":"08:00-08:45"}`. */
    @SerialName("sjkList") val sjkList: List<SisPeriodItem>? = null,

    /** Period -> start time, e.g. `{"1":"08:00"}`. Preferred source for wall-clock times. */
    @SerialName("zsMap") val zsMap: Map<String, String>? = null,

    /** Weekday -> name, e.g. `{"1":"星期一"}`. Used only to validate that we parsed a timetable. */
    @SerialName("xqjmcMap") val xqjmcMap: Map<String, String>? = null,

    /** Holiday / adjustment notes, when present. */
    @SerialName("jxhjkcList") val jxhjkcList: List<SisJsonObject>? = null,
    @SerialName("xqbzxxList") val xqbzxxList: List<SisJsonObject>? = null,

    /**
     * 学生信息，含当前学年学期（`XNM` / `XQM`）。
     * The most reliable term source: it is what the server actually answered for.
     */
    @SerialName("xsxx") val studentInfo: JsonObject? = null
)

/** A ZFSoft object whose shape we do not depend on; kept for diagnostics. */
typealias SisJsonObject = JsonObject

/**
 * One teaching session row.
 *
 * Field names are the ZFSoft wire names. All of them are nullable because different ZFSoft builds
 * omit different fields (for example the `xm` teacher field is absent in some 研究生 deployments).
 */
@Serializable
data class SisKbItem(
    /** 课程名称 */
    @SerialName("kcmc") val courseName: String? = null,
    /** 教师姓名 */
    @SerialName("xm") val teacher: String? = null,
    /** 校区名称 */
    @SerialName("xqmc") val campus: String? = null,
    /** 场地名称（教室） */
    @SerialName("cdmc") val location: String? = null,
    /** 周次段，如 "1-16周" / "1-8周,10-16周" / "1-16周(单)" */
    @SerialName("zcd") val weekDescription: String? = null,
    /** 星期几，1..7 */
    @SerialName("xqj") val weekday: String? = null,
    /** 节次，如 "1-2" */
    @SerialName("jcs") val periods: String? = null,
    /** 节次原始值，部分版本存在 */
    @SerialName("jcor") val periodsRaw: String? = null,
    /** 教学班名称 */
    @SerialName("jxbmc") val teachingClass: String? = null,
    /** 课程号 */
    @SerialName("kch") val courseCode: String? = null,
    /** 课程号（部分版本使用 kch_id） */
    @SerialName("kch_id") val courseCodeId: String? = null,
    /**
     * 单双周标记。
     *
     * WARNING: on the live 深圳河套学院 deployment this is `"1"` for **every** course, including
     * plain `1-14周` ones, so it must NOT be read as "odd weeks". Parity is taken from the 单/双
     * text in [weekDescription], and the authoritative week set is [oldWeekMask].
     */
    @SerialName("sxbj") val parityFlag: String? = null,
    /**
     * 周次位掩码：bit0 = 第 1 周。Observed `"16383"` = 0b11111111111111 = weeks 1..14, which matches
     * `zcd = "1-14周"`. When present and non-zero this is the most reliable week source.
     */
    @SerialName("oldzc") val oldWeekMask: String? = null,
    /** 节次位掩码（观察值 7 = 第 1-3 节）。 */
    @SerialName("oldjc") val oldPeriodMask: String? = null,
    /** 节次文本，如 "1-3节"。 */
    @SerialName("jc") val periodText: String? = null,
    /** 星期名称，如 "星期一"。 */
    @SerialName("xqjmc") val weekdayName: String? = null,
    /** 总学时 */
    @SerialName("zhxs") val totalHours: String? = null,
    /** 上课时间描述（部分版本） */
    @SerialName("sj") val timeText: String? = null,
    /** 学号（个别版本带在行里） */
    @SerialName("xh") val studentId: String? = null
)

@Serializable
data class SisPeriodItem(
    /** 节次编号 */
    @SerialName("sjk") val periodIndex: String? = null,
    @SerialName("sjbh") val periodNumber: String? = null,
    /** 节次名称 */
    @SerialName("sjmc") val periodName: String? = null,
    /** 时间范围，如 "08:00-08:45" */
    @SerialName("sj") val timeRange: String? = null,
    @SerialName("zcsj") val startTime: String? = null,
    @SerialName("jssj") val endTime: String? = null
)

/** Safe accessors over the raw JSON that never throw. */
object SisJson {

    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    fun JsonObject.element(key: String): JsonElement? = this[key]

    fun JsonObject.has(key: String): Boolean = this.containsKey(key)
}
