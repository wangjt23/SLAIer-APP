package com.slai.campus.domain.attendance

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalTime

/**
 * One day of the school's attendance (闸机刷卡) log.
 *
 * The system this replaces was a vague "打卡状态" guess. The real feature is an attendance ledger:
 * for every day it knows when the student entered and left, how many swipes there were, how long
 * they stayed, and whether the day counts as qualified.
 *
 * Source (confirmed by capture, 2026-09-09):
 * `POST https://stu.slai.edu.cn/a/edu/acm/swipe/weekGroupedByMonth`
 * body `startMonth=YYYY-MM&cycleWeek=`
 */
data class AttendanceRecord(
    val date: LocalDate,
    /** `Monday` … `Sunday`, as sent by the server. */
    val weekDay: String? = null,
    /** 工作日 / 周末 / 节假日 */
    val dayType: String? = null,
    val isHoliday: String? = null,
    /** 首次刷卡时间 = 进闸。 */
    val firstSwipe: LocalTime? = null,
    /** 最后刷卡时间 = 出闸。 */
    val lastSwipe: LocalTime? = null,
    val enterCount: Int? = null,
    val exitCount: Int? = null,
    /** 在馆时长，如 `10:23:57`。 */
    val durationText: String? = null,
    /** 在馆分钟数（服务端 `duration` 字段是秒）。 */
    val durationMinutes: Int? = null,
    /** `isQual`：是否合格。 */
    val qualified: Boolean? = null,
    val leave: Boolean? = null,
    val appeal: Boolean? = null,
    /** 所有刷卡时间，当服务端给出 `swipeTimes` 时。 */
    val swipes: List<LocalTime> = emptyList()
) {
    val hasSwipes: Boolean get() = enterCount != null && exitCount != null && (enterCount > 0 || exitCount > 0)

    /**
     * True when the last swipe has no matching exit yet, i.e. the student is still inside.
     * The college rule is "6 hours per working day", so an open stay must count as it elapses.
     */
    val isCurrentlyInside: Boolean get() = swipes.size % 2 == 1

    /**
     * Minutes actually accumulated today.
     *
     * Students leave and come back several times a day, so **pairs are summed** (1st→2nd, 3rd→4th, …)
     * rather than taking last-minus-first. When [now] is supplied and the student is still inside, the
     * open segment is counted up to [now] so the number grows while they are in the building.
     *
     * Falls back to the server's own `duration` field when the swipe list is unavailable.
     */
    fun checkedInMinutes(now: java.time.LocalTime? = null): Int? {
        if (swipes.size >= 2) {
            var total = 0
            var index = 0
            while (index + 1 < swipes.size) {
                val minutes = java.time.temporal.ChronoUnit.MINUTES.between(swipes[index], swipes[index + 1])
                if (minutes > 0) total += minutes.toInt()
                index += 2
            }
            if (index < swipes.size) {
                // Unpaired final swipe: still inside, count up to `now` when known.
                val start = swipes[index]
                val end = now ?: start
                val minutes = java.time.temporal.ChronoUnit.MINUTES.between(start, end)
                if (minutes > 0) total += minutes.toInt()
            }
            return total
        }

        durationMinutes?.let { if (it > 0) return it }

        val first = firstSwipe
        val last = lastSwipe
        if (first != null) {
            val end = last ?: now
            if (end != null) {
                val minutes = java.time.temporal.ChronoUnit.MINUTES.between(first, end)
                if (minutes > 0) return minutes.toInt()
            }
        }
        return null
    }

    /** `3 小时 12 分` / `48 分钟` / null when there is nothing to show. */
    fun checkedInText(now: java.time.LocalTime? = null, locale: java.util.Locale = java.util.Locale.getDefault()): String? =
        checkedInMinutes(now)?.let { formatMinutes(it, locale) }

    /** `08:52 → 21:30`，缺一头时只显示一头。 */
    val swipeRange: String
        get() {
            val first = firstSwipe?.format(HH_MM)
            val last = lastSwipe?.format(HH_MM)
            return when {
                first != null && last != null -> "$first → $last"
                first != null -> "$first → —"
                last != null -> "— → $last"
                else -> "无刷卡记录"
            }
        }

    companion object {
        val HH_MM: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

        /**
         * 人话时长：中文 `6 小时 12 分` / `48 分钟`，英文 `6h 12m` / `48m`。
         *
         * 带 [locale] 参数是因为同一个数字在两种语言里的写法完全不同 ——
         * 之前写死中文，切到英文后首页会显示 "10 小时 24 分"，很突兀。
         */
        fun formatMinutes(minutes: Int, locale: java.util.Locale = java.util.Locale.getDefault()): String {
            if (minutes < 60) {
                return if (locale.language.startsWith("zh")) "$minutes 分钟" else "${minutes}m"
            }
            val hours = minutes / 60
            val rest = minutes % 60
            return if (locale.language.startsWith("zh")) {
                if (rest == 0) "$hours 小时" else "$hours 小时 $rest 分"
            } else {
                if (rest == 0) "${hours}h" else "${hours}h ${rest}m"
            }
        }
    }
}

/** One teaching/attendance week as grouped by the server. */
data class AttendanceWeek(
    /** `第2周 (2026-09-07 至 2026-09-13)` */
    val label: String,
    /** `2026-09-07,2026-09-13` */
    val range: String,
    val records: List<AttendanceRecord>
) {
    val start: LocalDate? get() = range.split(',').firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val end: LocalDate? get() = range.split(',').getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Short label, e.g. `第2周`. */
    val shortLabel: String get() = label.substringBefore(' ').ifBlank { label }

    /**
     * 本周「学校判定合格」的日期，按日期先后排列。
     *
     * 合格与否完全来自学校的 `isQual`，这里只做排序 —— App 不推断任何一天是否合格。
     */
    private val qualifiedDatesInOrder: List<LocalDate>
        get() = records.asSequence()
            .filter { it.qualified == true }
            .map { it.date }
            .sorted()
            .toList()

    /** 本周学校判定合格的天数（可能超过 [COUNTED_DAYS_PER_WEEK]）。 */
    val qualifiedCount: Int get() = qualifiedDatesInOrder.size

    /**
     * 计入打卡的天数。
     *
     * 学院口径是**一周 7 天里任意 5 天**，不是"必须工作日"：工作日缺的那天可以用周末补。
     * 所以一周最多只计 [COUNTED_DAYS_PER_WEEK] 天，多出来的合格日不再累加。
     */
    val countedCount: Int get() = minOf(qualifiedCount, COUNTED_DAYS_PER_WEEK)

    /** 计入打卡的日期：按日期先后取前 [COUNTED_DAYS_PER_WEEK] 个合格日。 */
    val countedDates: Set<LocalDate> get() = qualifiedDatesInOrder.take(COUNTED_DAYS_PER_WEEK).toSet()

    /**
     * 学校判定合格、但本周已满 5 天所以不计入的日期。
     *
     * 明细里必须说清楚，否则用户会问"学校都说合格了，为什么天数对不上"。
     */
    val overflowDates: Set<LocalDate> get() = qualifiedDatesInOrder.drop(COUNTED_DAYS_PER_WEEK).toSet()

    companion object {
        /**
         * 一周最多计入的打卡天数。
         *
         * 实测支持这个口径：抓到的 2026-09 `stats` 是 `totalWorkdays=20`、`requiredPunches=20`，
         * 而该月有 22 个工作日 —— 20 恰好是 4 周 × 5 天，而不是"每个工作日一天"。
         * 学校另用 `maxAllowedRestdayPunches=3` 记周末补卡额度。
         */
        const val COUNTED_DAYS_PER_WEEK = 5
    }
}

/**
 * The school's own monthly verdict, from the `stats` block of the live response:
 * ```json
 * {"totalWorkdays":20,"requiredPunches":20,"actualWorkdayPunches":7,"totalValidPunches":7,
 *  "isMonthlyQualified":false,"qualificationMessage":"不合格","maxAllowedRestdayPunches":3}
 * ```
 */
data class AttendanceStats(
    val totalWorkdays: Int? = null,
    val requiredPunches: Int? = null,
    val actualWorkdayPunches: Int? = null,
    val totalValidPunches: Int? = null,
    val maxAllowedRestdayPunches: Int? = null,
    val restdayPunches: Int? = null,
    val monthlyQualified: Boolean? = null,
    val qualificationMessage: String? = null,
    /** Any extra keys the server sends, kept for diagnostics. */
    val extra: Map<String, String> = emptyMap()
) {
    val hasAnything: Boolean
        get() = totalWorkdays != null || actualWorkdayPunches != null || monthlyQualified != null

    /**
     * 本月**应达标天数**（学校口径）。
     *
     * 只用来和 [effectiveDays] 配成「7 / 20 天」显示；`actualWorkdayPunches`（只数工作日）
     * 不再单独出现在界面上 —— 它和网站对不上，是实测踩过的坑。
     */
    val requiredDays: Int? get() = requiredPunches ?: totalWorkdays

    /**
     * 学校口径的**有效打卡总天数** = 工作日达标 + 周末/节假日照常打卡。
     *
     * 这正是学院网站上那个「有效打卡 N 天」：实测某月工作日 5 天 + 法定节假日 2 天，
     * 网站显示 7 天，而 `actualWorkdayPunches` 只给 5 —— 两个数都没错，是"只数工作日"和"总数"的区别。
     * 缺 `actualRestdayPunches` 时退回工作日计数，绝不凭空加。
     *
     * 与「剩余补打卡机会」（[maxAllowedRestdayPunches]，漏卡后的补录机会）**不是一回事**：
     * 周末来打卡不会消耗那个额度。
     */
    val effectiveDays: Int?
        get() = actualWorkdayPunches?.let { it + (restdayPunches ?: 0) }
}

/** A month of attendance, as returned by one request. */
data class AttendanceMonth(
    val month: String,
    val weeks: List<AttendanceWeek> = emptyList(),
    /** Server-provided summary counters, keyed by their original field name. */
    val stats: Map<String, String> = emptyMap(),
    val summary: AttendanceStats = AttendanceStats()
) {
    val records: List<AttendanceRecord> get() = weeks.flatMap { it.records }

    fun recordFor(date: LocalDate): AttendanceRecord? = records.firstOrNull { it.date == date }

    val lastDate: LocalDate? get() = records.maxOfOrNull { it.date }
}

/** How the attendance data was obtained on this device. */
data class AttendanceSyncState(
    val lastSuccessAt: java.time.Instant? = null,
    val lastAttemptAt: java.time.Instant? = null,
    val lastError: String? = null,
    val monthsCached: List<String> = emptyList()
)

interface AttendanceRepository {

    /** Cached attendance for [date] (today by default). Reads Room only. */
    fun observeDay(date: LocalDate): Flow<AttendanceRecord?>

    /** Cached attendance for a whole month, ordered by date. Reads Room only. */
    fun observeMonth(month: String): Flow<AttendanceMonth>

    fun observeSyncState(): Flow<AttendanceSyncState>

    /** Fetches (or refreshes) one month from the school. */
    suspend fun refresh(month: String): AttendanceRefreshResult

    /** The month currently held in cache, if any. */
    suspend fun cachedMonths(): List<String>

    /** 某一天的闸机流水（本地缓存）。用于自己计算当天累计时长。 */
    fun observePunches(date: LocalDate): Flow<List<AttendancePunch>>

    /**
     * 一个日期区间的闸机流水。
     *
     * 月度明细里会混进上个月最后几天的课（学校按教学周分组），而且学校月度接口
     * **从不返回** `firstSwipe`/`lastSwipe`，所以每天的进出时间只能来自这里 ——
     * 只查"今天"是不够的。
     */
    fun observePunches(from: LocalDate, to: LocalDate): Flow<List<AttendancePunch>>

    /** 拉取 [from]..[to] 的闸机流水。 */
    suspend fun refreshPunches(from: LocalDate, to: LocalDate): AttendanceRefreshResult

    /** 最近一次流水同步的错误信息（用于界面提示）。 */
    fun observePunchSyncState(): Flow<AttendanceSyncState>
}

sealed interface AttendanceRefreshResult {
    data class Success(val month: String, val days: Int) : AttendanceRefreshResult
    data object SessionExpired : AttendanceRefreshResult
    data class Offline(val cached: Boolean) : AttendanceRefreshResult

    /**
     * 有网，但**连不上学校主机**（DNS / 连接超时 / TLS 失败）。
     *
     * 和 [Offline] 分开是有原因的：学生考勤系统在校园网外常常连不上，用户"刷脸出闸 → 走出校园"
     * 之后就会一直是这个状态 —— 此时界面不能只说"离线"，而要提示回到校园网再刷新，
     * 否则用户会以为"我明明出闸了，为什么还在馆里计时"。
     */
    data class Unreachable(val reason: String) : AttendanceRefreshResult
    data class SchemaChanged(val reason: String) : AttendanceRefreshResult
    data class ServerError(val code: Int) : AttendanceRefreshResult
    data class Failed(val reason: String) : AttendanceRefreshResult

    val isSuccess: Boolean get() = this is Success

    /** 数据可能已经过期：连不上学校，界面要给出"回校园网再刷"的提示。 */
    val needsCampusNetworkHint: Boolean get() = this is Unreachable
}

/**
 * 把"网络层失败"翻译成给用户看的结果。
 *
 * 设备有网却连不上学校主机，和"设备压根没网"是两件事：前者多半是人已经离开校园网
 * （学生系统在校外常常不可达），提示应该是"回校园网再刷新"，而不是"你现在离线"。
 * 抽成纯函数便于单测。
 */
fun networkFailureResult(hasNetwork: Boolean, hasCache: Boolean, reason: String): AttendanceRefreshResult =
    if (hasNetwork) AttendanceRefreshResult.Unreachable(reason) else AttendanceRefreshResult.Offline(hasCache)
