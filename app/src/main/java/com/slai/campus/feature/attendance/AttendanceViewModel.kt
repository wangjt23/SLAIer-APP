package com.slai.campus.feature.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.core.session.SessionManager
import com.slai.campus.core.session.SessionStore
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.web.AppUrlProvider
import com.slai.campus.core.web.AppUrls
import com.slai.campus.domain.attendance.AttendanceMonth
import com.slai.campus.domain.attendance.AttendancePunch
import com.slai.campus.domain.attendance.AttendanceRecord
import com.slai.campus.domain.attendance.DailyAttendance
import com.slai.campus.domain.attendance.PunchPairing
import com.slai.campus.domain.attendance.AttendanceRefreshResult
import com.slai.campus.domain.attendance.AttendanceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class AttendanceUiState(
    val month: String = "",
    val today: AttendanceRecord? = null,
    val monthData: AttendanceMonth = AttendanceMonth(""),
    val refreshing: Boolean = false,
    val lastResult: AttendanceRefreshResult? = null,
    val urls: AppUrls? = null,
    val stuNeedsLogin: Boolean = false,
    val todayDate: LocalDate = LocalDate.now(),
    /** College rule: 6 hours of accumulated check-in on a working day. */
    val dailyGoalMinutes: Int = 360,
    val nowDateTime: java.time.LocalDateTime = java.time.LocalDateTime.now(),
    /** 今天由闸机流水配对算出的在馆情况（首选数据源）。 */
    val todayDaily: DailyAttendance? = null,
    /**
     * 本月每一天由闸机流水算出的在馆情况。
     *
     * 学校月度接口对**每一天**都返回空的 `firstSwipe`/`lastSwipe`（recon10 实测），
     * 所以明细里的进出时间只能自己按流水配对算；不这么做就只能显示"无刷卡记录"，
     * 而旁边又写着学校给的在馆时长 —— 两句话互相打架。
     */
    val daily: Map<LocalDate, DailyAttendance> = emptyMap(),
    /** 当前界面语言，用来格式化时长与日期。 */
    val locale: java.util.Locale = java.util.Locale.getDefault()
) {
    val now: java.time.LocalTime get() = nowDateTime.toLocalTime()

    /**
     * 今日累计打卡分钟数。
     *
     * 优先用**闸机流水自己配对计算**：考勤汇总接口的「当日累计时长」对当天恒为 0（未结算），
     * 只有刷卡流水带具体进出时间。没有流水时回退到学校给的字段。
     */
    val todayMinutes: Int?
        get() {
            val fromPunches = todayDaily?.takeIf { it.punches.isNotEmpty() }
            if (fromPunches != null) return fromPunches.minutesAt(nowDateTime)
            return today?.checkedInMinutes(now)
        }

    val currentlyInside: Boolean
        get() = todayDaily?.currentlyInsideAt(nowDateTime) == true || today?.isCurrentlyInside == true

    /** 今天有没有"进了没出、已过次日 05:00 作废"的记录。 */
    val todayHasDiscarded: Boolean
        get() = todayDaily?.hasDiscardedSessionAt(nowDateTime) == true

    val sessions: List<com.slai.campus.domain.attendance.PunchSession>
        get() = todayDaily?.sessions.orEmpty()

    val todayProgress: Float
        get() = todayMinutes?.let { (it.toFloat() / dailyGoalMinutes).coerceIn(0f, 1f) } ?: 0f

    val goalReached: Boolean get() = (todayMinutes ?: 0) >= dailyGoalMinutes

    /** 距离目标的分钟数；< 0 表示已达标。UI 负责翻译成文案。 */
    val remainingMinutes: Int?
        get() {
            val done = todayMinutes ?: return null
            return dailyGoalMinutes - done
        }

    val todayMinutesText: String?
        get() = todayMinutes?.let { AttendanceRecord.formatMinutes(it, locale) }

    val days: List<AttendanceRecord> get() = monthData.records

    /** 某一天由流水算出的累计分钟数（今天按 [nowDateTime] 把未闭合的一段算进去）。 */
    fun punchMinutes(date: LocalDate): Int? {
        val d = daily[date] ?: return null
        if (d.punches.isEmpty()) return null
        return d.minutesAt(nowDateTime)
    }

    fun punchDay(date: LocalDate): DailyAttendance? = daily[date]?.takeIf { it.punches.isNotEmpty() }

    /** 这一天是否有已作废的"进了没出"。 */
    fun hasDiscarded(date: LocalDate): Boolean =
        daily[date]?.hasDiscardedSessionAt(nowDateTime) == true

    /** Days that actually have a swipe, newest first. */
    val swipedDays: List<AttendanceRecord> get() = days.filter { it.hasSwipes }.sortedByDescending { it.date }

    val hasData: Boolean get() = days.isNotEmpty()

    val qualifiedCount: Int get() = days.count { it.qualified == true }
}

/**
 * Attendance screen state.
 *
 * The month is a real query parameter (`startMonth=YYYY-MM`), so the screen keeps one month at a
 * time and lets the user step through months; each month is cached independently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AttendanceViewModel @Inject constructor(
    private val repository: AttendanceRepository,
    private val sessionManager: SessionManager,
    private val urlProvider: AppUrlProvider,
    private val sessionStore: SessionStore,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private val month = MutableStateFlow(currentMonth())
    private val refreshing = MutableStateFlow(false)
    private val lastResult = MutableStateFlow<AttendanceRefreshResult?>(null)

    private val todayDate: LocalDate get() = timeProvider.today()

    private val monthFlow = month.flatMapLatest { repository.observeMonth(it) }

    private val clock = MutableStateFlow(java.time.LocalDateTime.now())

    private data class Core(
        val month: String,
        val today: AttendanceRecord?,
        val monthData: AttendanceMonth,
        val refreshing: Boolean,
        val lastResult: AttendanceRefreshResult?
    )

    // kotlinx combine() has typed overloads only up to 5 flows, so the pieces are folded together.
    private val todayPunches = repository.observePunches(todayDate)

    /**
     * 明细里出现的第一天可能属于上个月 —— 学校按**教学周**分组，2026-09 的第 1 周就是
     * 08-31 ~ 09-06 —— 所以闸机流水必须覆盖"上个月最后一周 ~ 本月最后一天"。
     * 只查日历月会把 08-31 整个漏掉，那一天就永远显示"无刷卡记录"（实测 08-31 有 4 条流水）。
     */
    private fun punchSpanFor(monthKey: String): Pair<LocalDate, LocalDate> {
        val year = monthKey.substringBefore('-').toIntOrNull() ?: todayDate.year
        val m = monthKey.substringAfter('-').take(2).toIntOrNull() ?: todayDate.monthValue
        val first = LocalDate.of(year, m, 1)
        return first.minusDays(7) to first.plusMonths(1).minusDays(1)
    }

    private val monthPunches = month.flatMapLatest { key ->
        val (from, to) = punchSpanFor(key)
        repository.observePunches(from, to)
    }

    private data class Punches(
        val today: List<AttendancePunch>,
        val month: List<AttendancePunch>
    )

    private val allPunches = combine(todayPunches, monthPunches) { t, m -> Punches(t, m) }

    private data class Core2(
        val core: Core,
        val punches: Punches
    )

    private val core = combine(
        month,
        repository.observeDay(todayDate),
        monthFlow,
        allPunches,
        refreshing
    ) { monthValue, today, monthData, punches, isRefreshing ->
        Core2(Core(monthValue, today, monthData, isRefreshing, null), punches)
    }.let { flow ->
        combine(flow, lastResult) { c, result -> Core2(c.core.copy(lastResult = result), c.punches) }
    }

    private val goal = sessionStore.attendanceGoalMinutes

    /** (目标分钟数, 生效的 Locale)。两者都是纯值，合成一个 flow 免得 combine 超过 5 个。 */
    private val goalAndLocale = combine(goal, sessionStore.appLanguage) { minutes, language ->
        minutes to (language.localeOrNull ?: java.util.Locale.getDefault())
    }

    val state: StateFlow<AttendanceUiState> = combine(
        core,
        urlProvider.urls,
        sessionManager.state,
        goalAndLocale,
        clock
    ) { c2, urls, session, goalAndLang, now ->
        val (goalMinutes, locale) = goalAndLang
        val c = c2.core
        AttendanceUiState(
            month = c.month,
            today = c.today,
            monthData = c.monthData,
            refreshing = c.refreshing,
            lastResult = c.lastResult,
            todayDaily = PunchPairing.of(todayDate, c2.punches.today, now),
            daily = PunchPairing.daily(c2.punches.month, now),
            urls = urls,
            stuNeedsLogin = session.stu == SessionState.EXPIRED || session.stu == SessionState.NEEDS_LOGIN,
            todayDate = todayDate,
            dailyGoalMinutes = goalMinutes,
            nowDateTime = now,
            locale = locale
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AttendanceUiState(month = currentMonth()))

    init {
        // Load the current month once on first open.
        viewModelScope.launch {
            repository.refresh(month.value)
            refreshPunches()
        }
        // Keep "still inside" time ticking without hammering the server.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                clock.value = java.time.LocalDateTime.now()
            }
        }
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                lastResult.value = repository.refresh(month.value)
                refreshPunches()
            } finally {
                refreshing.value = false
            }
        }
    }

    /** 拉取闸机流水（当天时长、以及明细里每天的进出时间都靠它）。 */
    private suspend fun refreshPunches() {
        val (from, to) = punchSpanFor(month.value)
        repository.refreshPunches(from, to)
    }

    fun previousMonth() {
        month.value = shiftMonth(month.value, -1)
    }

    fun nextMonth() {
        month.value = shiftMonth(month.value, 1)
    }

    fun currentMonth() = currentMonthString()

    private fun currentMonthString(): String {
        val today = todayDate
        return "%04d-%02d".format(today.year, today.monthValue)
    }

    private fun shiftMonth(value: String, delta: Long): String {
        val year = value.substring(0, 4).toIntOrNull() ?: return value
        val monthValue = value.substring(5).toIntOrNull() ?: return value
        val base = LocalDate.of(year, monthValue, 1).plusMonths(delta)
        return "%04d-%02d".format(base.year, base.monthValue)
    }
}
