package com.slai.campus.domain.attendance

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** 闸机事件方向。 */
enum class PunchDirection { IN, OUT, UNKNOWN;

    companion object {
        fun fromChinese(value: String?): PunchDirection = when (value?.trim()) {
            "进门", "进", "入", "in", "IN" -> IN
            "出门", "出", "out", "OUT" -> OUT
            else -> UNKNOWN
        }
    }
}

/**
 * 一条闸机刷卡记录。
 *
 * Source (confirmed by capture, 2026-09-09):
 * `GET /a/edu/acm/swipe/listData?page=1&limit=N&startTime=&endTime=`
 * ```json
 * {"code":0,"count":102,"data":[{"swipeTime":"2026-09-09 18:42:26","eventType":"进门",
 *   "channelName":"闸机-东5-入_门禁通道_1","swipeType":"教学楼",
 *   "openingType":"人脸合法开门","openingResult":"成功","swipeDate":"2026-09-09"}]}
 * ```
 */
data class AttendancePunch(
    val id: String,
    val time: LocalDateTime,
    val direction: PunchDirection,
    /** 门禁通道，如 `闸机-东5-入_门禁通道_1`。 */
    val channel: String? = null,
    /** 刷卡类型，如 `教学楼`。 */
    val place: String? = null,
    val openingType: String? = null,
    val result: String? = null
) {
    val date: LocalDate get() = time.toLocalDate()
    val timeOfDay: LocalTime get() = time.toLocalTime()
}

/** 一段在馆区间（进门 → 出门）。 */
data class PunchSession(
    val from: LocalDateTime,
    /** null = 尚未出门（仍在馆内）。 */
    val to: LocalDateTime? = null
) {
    /**
     * 这一次进门最晚有效到什么时候：**次日的 [OPEN_CUTOFF]**。
     *
     * 学院的规定：只刷卡进了馆、到第二天凌晨 5 点还没刷出去，这一次进门就作废（算 0），
     * 而不是一直累加下去。没有这条规则时，一次忘刷的出门会每天继续涨 ——
     * 实测 8/31 21:03 那次未闭合的进门到 9/10 被算成了 230 小时，
     * 整天数字显示成"240 小时 24 分"。
     */
    val expiresAt: LocalDateTime
        get() = from.toLocalDate().plusDays(1).atTime(OPEN_CUTOFF)

    val isOpen: Boolean get() = to == null

    /** 到 [now] 为止还算是"在馆中"（未闭合，且还没到作废时刻）。 */
    fun isStillOpenAt(now: LocalDateTime): Boolean = to == null && now.isBefore(expiresAt)

    /** 因为"进了没出"而被判作废。 */
    fun isDiscardedAt(now: LocalDateTime): Boolean = to == null && !now.isBefore(expiresAt)

    fun minutesAt(now: LocalDateTime): Int {
        val end = to ?: run {
            // 过了次日 05:00 仍未出门 → 这一段整段作废。
            if (!now.isBefore(expiresAt)) return 0
            now
        }
        val minutes = ChronoUnit.MINUTES.between(from, end)
        return if (minutes > 0) minutes.toInt() else 0
    }

    fun textAt(now: LocalDateTime): String {
        val start = from.toLocalTime().format(AttendanceRecord.HH_MM)
        if (isDiscardedAt(now)) return "$start → 未刷卡出门（作废）"
        val end = (to ?: now).toLocalTime().format(AttendanceRecord.HH_MM)
        return "$start → $end"
    }

    companion object {
        /** 未闭合的进门在这个时刻（次日）之后作废。 */
        val OPEN_CUTOFF: LocalTime = LocalTime.of(5, 0)
    }
}

/** 某一天由闸机记录算出的考勤。 */
data class DailyAttendance(
    val date: LocalDate,
    val sessions: List<PunchSession> = emptyList(),
    val punches: List<AttendancePunch> = emptyList()
) {
    /** 累计在馆分钟数；仍在馆内的那一段按 [now] 计算，作废的那段算 0。 */
    fun minutesAt(now: LocalDateTime): Int = sessions.sumOf { it.minutesAt(now) }

    fun textAt(now: LocalDateTime, locale: java.util.Locale = java.util.Locale.getDefault()): String =
        AttendanceRecord.formatMinutes(minutesAt(now), locale)

    /** 到 [now] 为止是否真的还在馆内（未闭合且未过次日 05:00）。 */
    fun currentlyInsideAt(now: LocalDateTime): Boolean =
        sessions.lastOrNull()?.isStillOpenAt(now) == true

    /** 这一天是否有"进了没出、已作废"的记录，界面上要说明一下。 */
    fun hasDiscardedSessionAt(now: LocalDateTime): Boolean =
        sessions.any { it.isDiscardedAt(now) }

    val firstIn: LocalTime? get() = punches.firstOrNull { it.direction == PunchDirection.IN }?.timeOfDay

    val lastOut: LocalTime? get() = punches.lastOrNull { it.direction == PunchDirection.OUT }?.timeOfDay

    val enterCount: Int get() = punches.count { it.direction == PunchDirection.IN }

    val exitCount: Int get() = punches.count { it.direction == PunchDirection.OUT }
}

/**
 * 把闸机流水配对成在馆区间并累加。
 *
 * 规则（针对真实数据的三个坑）：
 *  1. **同秒同方向去重**：学生从相邻闸机通过时，服务端会给出两条同一秒的 `出门`（东5-出、东6-出），
 *     不去重会把一次出门算成两次；
 *  2. **按时间升序配对**：`进门` 开一段，下一个 `出门` 收一段；
 *  3. **未闭合的进门**：最后一条是 `进门` 且没有对应 `出门`，说明人还在馆内，按 [now] 累计。
 *
 * 极短的抖动（例如 15:27:56 进门 → 15:27:59 出门，3 秒）照常计入，因为它对总数无实质影响，
 * 而过滤阈值反而可能误删真实数据。
 */
object PunchPairing {

    /** `swipeType` 取值之一：宿舍楼门禁。学校**不**统计它。 */
    private const val DORMITORY = "宿舍楼"

    /**
     * 只保留**教学楼**闸机。
     *
     * 实测（recon10，2026-09-01…09-09 全量流水）：只用 `swipeType = 教学楼` 配对，
     * 每天的分钟数与学校自己的 `durationStr` **逐条吻合** ——
     *
     * ```text
     * 9/1  539分 = 08:59:11      9/2  681分 = 11:20:52
     * 9/3  404分 = 06:44:09      9/4  649分 = 10:49:07
     * 9/7  646分 = 10:46:35      9/8  839分 = 13:58:35
     * 9/9  576分 = 09:36:39
     * ```
     *
     * 而把 `宿舍楼` 也算进去，9/7 会变成 668 分（11:08），比学校多 22 分钟 ——
     * 早上从宿舍出门、晚上回宿舍，会被配对成一段并不在教室里的"在馆"时间。
     * 学院要求的"工作日 6 小时"指的是教学楼，所以宿舍楼必须排除。
     *
     * `place` 为 null（别的学校/别的版本）时不做任何过滤。
     */
    fun teachingBuildingOnly(punches: List<AttendancePunch>): List<AttendancePunch> =
        punches.filter { it.place?.trim() != DORMITORY }

    fun daily(punches: List<AttendancePunch>, now: LocalDateTime): Map<LocalDate, DailyAttendance> =
        teachingBuildingOnly(punches).groupBy { it.date }.mapValues { (date, dayPunches) -> of(date, dayPunches, now) }

    fun of(date: LocalDate, dayPunches: List<AttendancePunch>, now: LocalDateTime): DailyAttendance {
        val ordered = dedupe(teachingBuildingOnly(dayPunches))
        val sessions = mutableListOf<PunchSession>()
        var open: LocalDateTime? = null

        ordered.forEach { punch ->
            when (punch.direction) {
                PunchDirection.IN -> {
                    // 两次进门之间没有出门：保留较早的那次（人一直在馆内）。
                    if (open == null) open = punch.time
                }
                PunchDirection.OUT -> {
                    val start = open
                    if (start != null) {
                        sessions += PunchSession(start, punch.time)
                        open = null
                    }
                    // 没有对应进门的出门：忽略（可能是别的门或数据缺边）。
                }
                PunchDirection.UNKNOWN -> Unit
            }
        }

        if (open != null) sessions += PunchSession(open!!, null)

        return DailyAttendance(
            date = date,
            sessions = sessions,
            punches = ordered
        )
    }

    /** 同秒同方向只保留一条；同秒一进一出都保留（它们是不同事件）。 */
    private fun dedupe(punches: List<AttendancePunch>): List<AttendancePunch> =
        punches.sortedBy { it.time }
            .distinctBy { it.time.toString() + "|" + it.direction }
}
