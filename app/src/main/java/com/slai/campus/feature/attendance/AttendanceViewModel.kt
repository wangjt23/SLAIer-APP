package com.slai.campus.feature.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.session.SessionManager
import com.slai.campus.core.session.SessionStore
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.web.AppUrlProvider
import com.slai.campus.core.web.AppUrls
import com.slai.campus.domain.attendance.AttendanceMonth
import com.slai.campus.domain.attendance.CurrentAttendance
import com.slai.campus.domain.attendance.attendanceDate
import com.slai.campus.domain.attendance.observeCurrentAttendance
import com.slai.campus.domain.attendance.refreshMonthWithCurrentDay
import com.slai.campus.domain.attendance.AttendanceRecord
import com.slai.campus.domain.attendance.AttendancePunch
import com.slai.campus.domain.attendance.DailyAttendance
import com.slai.campus.domain.attendance.PunchPairing
import com.slai.campus.domain.attendance.AttendanceRefreshResult
import com.slai.campus.domain.attendance.monthPunchRange
import com.slai.campus.domain.attendance.AttendanceRepository
import com.slai.campus.domain.attendance.latestAttendanceRefresh
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
    val todayDate: LocalDate = attendanceDate(java.time.LocalDateTime.now()),
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
    val locale: java.util.Locale = java.util.Locale.getDefault(),
    /** Full current punch range, including stays entered after the selected attendance date. */
    val currentPunches: List<AttendancePunch> = emptyList()
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
            today?.takeIf { it.leave == true }?.let {
                return it.displayMinutes(todayDaily, nowDateTime)
            }
            val fromPunches = todayDaily?.takeIf { it.punches.isNotEmpty() }
            if (fromPunches != null) return fromPunches.minutesAt(nowDateTime)
            return today?.checkedInMinutesAt(nowDateTime)
        }

    val currentlyInside: Boolean
        get() {
            if (currentPunches.isNotEmpty()) return PunchPairing.currentlyInsideAt(currentPunches, nowDateTime)
            todayDaily?.takeIf { it.punches.isNotEmpty() }?.let {
                return PunchPairing.currentlyInsideAt(it.punches, nowDateTime)
            }
            todayDaily?.takeIf { it.sessions.isNotEmpty() }?.let { return it.currentlyInsideAt(nowDateTime) }
            return today?.isCurrentlyInsideAt(nowDateTime) == true
        }

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

    fun punchDay(date: LocalDate): DailyAttendance? = daily[date]?.takeIf { it.punches.isNotEmpty() }

    /** 这一天是否有已作废的"进了没出"。 */
    fun hasDiscarded(date: LocalDate): Boolean =
        daily[date]?.hasDiscardedSessionAt(nowDateTime) == true

    val hasData: Boolean get() = days.isNotEmpty()


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

    private val monthFlow = month.flatMapLatest { repository.observeMonth(it) }

    private val clock = MutableStateFlow(timeProvider.nowDateTime())

    private data class Core(
        val month: String,
        val current: CurrentAttendance,
        val monthData: AttendanceMonth,
        val monthPunches: List<com.slai.campus.domain.attendance.AttendancePunch>,
        val refreshing: Boolean,
        val lastResult: AttendanceRefreshResult?
    )

    // kotlinx combine() has typed overloads only up to 5 flows, so the pieces are folded together.
    private val currentAttendance = repository.observeCurrentAttendance(clock)

    /**
     * 明细里出现的第一天可能属于上个月 —— 学校按**教学周**分组，2026-09 的第 1 周就是
     * 08-31 ~ 09-06 —— 所以闸机流水必须覆盖"上个月最后一周 ~ 本月最后一天"。
     * 只查日历月会把 08-31 整个漏掉，那一天就永远显示"无刷卡记录"（实测 08-31 有 4 条流水）。
     */
    private fun punchSpanFor(monthKey: String): Pair<LocalDate, LocalDate> =
        monthPunchRange(java.time.YearMonth.parse(monthKey))

    private val monthPunches = month.flatMapLatest { key ->
        val (from, to) = punchSpanFor(key)
        repository.observePunches(from, to)
    }

    private val core = combine(
        month,
        currentAttendance,
        monthFlow,
        monthPunches,
        refreshing
    ) { monthValue, current, monthData, punches, isRefreshing ->
        Core(monthValue, current, monthData, punches, isRefreshing, null)
    }.let { flow ->
        combine(flow, lastResult) { c, result -> c.copy(lastResult = result) }
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
    ) { c, urls, session, goalAndLang, now ->
        val (goalMinutes, locale) = goalAndLang
        AttendanceUiState(
            month = c.month,
            today = c.current.record,
            monthData = c.monthData,
            refreshing = c.refreshing,
            lastResult = c.lastResult,
            todayDaily = PunchPairing.of(c.current.date, c.current.punches, now),
            daily = PunchPairing.daily(c.monthPunches, now),
            urls = urls,
            stuNeedsLogin = session.stu == SessionState.EXPIRED || session.stu == SessionState.NEEDS_LOGIN,
            todayDate = c.current.date,
            dailyGoalMinutes = goalMinutes,
            nowDateTime = now,
            locale = locale,
            currentPunches = c.current.punches
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AttendanceUiState(
        month = currentMonth(), todayDate = attendanceDate(clock.value), nowDateTime = clock.value
    ))

    init {
        // Load the current month once on first open.
        refresh()
        // Keep "still inside" time ticking without hammering the server.
        viewModelScope.launch {
            timeProvider.observeDateTime().collect(::updateClock)
        }
        // A foreground recovery runs outside this ViewModel; its complete refresh must replace
        // an old transport error here as well. Authentication alone proves no punch data.
        viewModelScope.launch {
            var appliedSequence = 0L
            var observedOwner: String? = null
            var observedMonth: String? = null
            var ownerInitialized = false
            combine(repository.refreshUpdates, sessionManager.state, month) { updates, session, selected ->
                val owner = session.accountHashOf(SchoolSystem.STU)
                if (observedMonth != selected) appliedSequence = 0L
                observedMonth = selected
                if (ownerInitialized && observedOwner != owner) {
                    appliedSequence = 0L
                    // Initial DataStore hydration may follow the first completed request.
                    if (observedOwner != null) lastResult.value = null
                }
                observedOwner = owner
                ownerInitialized = true
                val (from, to) = punchSpanFor(selected)
                latestAttendanceRefresh(updates, owner, sessionManager.accountVersion(SchoolSystem.STU), selected, from, to)
            }.collect { update ->
                if (update != null && update.sequence > appliedSequence) {
                    appliedSequence = update.sequence
                    lastResult.value = update.result
                }
            }
        }
    }

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                updateClock(timeProvider.nowDateTime())
                // A historical-month refresh may run a second query for the current day.
                // Shared updates carry identity; its combined local return must retain it too.
                val requestedAccountVersion = sessionManager.accountVersion(SchoolSystem.STU)
                val result = refreshSelectedAttendanceMonth(
                    selectedMonth = { month.value },
                    now = timeProvider::nowDateTime,
                    refreshMonth = { selected, now -> repository.refreshMonthWithCurrentDay(selected, now) },
                    updateClock = ::updateClock
                )
                if (result !is AttendanceRefreshResult.Superseded &&
                    sessionManager.accountVersion(SchoolSystem.STU) == requestedAccountVersion) {
                    lastResult.value = result
                }
            } finally {
                refreshing.value = false
            }
        }
    }

    fun previousMonth() {
        if (refreshing.value) return
        month.value = shiftMonth(month.value, -1)
        lastResult.value = null
        refresh()
    }

    fun nextMonth() {
        if (refreshing.value) return
        month.value = shiftMonth(month.value, 1)
        lastResult.value = null
        refresh()
    }

    fun currentMonth() = currentMonthString()

    private fun currentMonthString(): String {
        return java.time.YearMonth.from(attendanceDate(timeProvider.nowDateTime())).toString()
    }

    private fun updateClock(now: java.time.LocalDateTime) {
        val previousMonth = java.time.YearMonth.from(attendanceDate(clock.value)).toString()
        val currentMonth = java.time.YearMonth.from(attendanceDate(now)).toString()
        // Follow the current month across its boundary, but preserve a historical month selection.
        val changedMonth = previousMonth != currentMonth && month.value == previousMonth
        if (changedMonth) {
            month.value = currentMonth
            lastResult.value = null
        }
        clock.value = now
        // An in-flight refresh will follow the new selection before publishing its result.
        // Otherwise start the new month's first fetch immediately.
        if (changedMonth) refresh()
    }

    private fun shiftMonth(value: String, delta: Long): String {
        val year = value.substring(0, 4).toIntOrNull() ?: return value
        val monthValue = value.substring(5).toIntOrNull() ?: return value
        val base = LocalDate.of(year, monthValue, 1).plusMonths(delta)
        return "%04d-%02d".format(base.year, base.monthValue)
    }
}
