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
import com.slai.campus.domain.attendance.AttendancePeriod
import com.slai.campus.domain.attendance.AttendancePeriodResult
import com.slai.campus.domain.attendance.attendancePeriodPunchRange
import com.slai.campus.domain.attendance.currentAttendancePeriod
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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

    private val periods = MutableStateFlow<List<AttendancePeriod>>(emptyList())
    private val month = MutableStateFlow(currentMonth())
    private val refreshing = MutableStateFlow(false)
    private val lastResult = MutableStateFlow<AttendanceRefreshResult?>(null)
    private data class ResultContext(val owner: String?, val accountVersion: Long, val month: String)
    private data class LocalCompletion(val context: ResultContext, val consumedSequence: Long)
    private var resultContext: ResultContext? = null
    private var localCompletion: LocalCompletion? = null
    // A deliberate history selection must survive clock ticks and background session recovery.
    private var followingCurrentPeriod = true

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

    /** Keep the school's complete period, plus the adjacent overnight entry/exit context. */
    private fun punchSpanFor(monthKey: String): Pair<LocalDate, LocalDate> =
        periods.value.singleOrNull { it.month == monthKey }?.let(::attendancePeriodPunchRange)
            ?: monthPunchRange(java.time.YearMonth.parse(monthKey))

    private data class MonthPunches(val month: String, val punches: List<AttendancePunch>)

    private val monthPunches = combine(month, periods) { key, _ -> key to punchSpanFor(key) }
        .distinctUntilChanged().flatMapLatest { (key, span) ->
            repository.observePunches(span.first, span.second).map { MonthPunches(key, it) }
        }

    private val core = combine(
        month,
        currentAttendance,
        monthFlow,
        monthPunches,
        refreshing
    ) { monthValue, current, monthData, punches, isRefreshing ->
        Core(monthValue, current, monthData.takeIf { it.month == monthValue } ?: AttendanceMonth(monthValue),
            punches.punches.takeIf { punches.month == monthValue }.orEmpty(), isRefreshing, null)
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
        viewModelScope.launch {
            var previousOwner: String? = null
            sessionManager.state.map { it.accountHashOf(SchoolSystem.STU) }.distinctUntilChanged()
                .flatMapLatest { owner ->
                    // Initial preference hydration is not a deliberate account switch.
                    if (previousOwner != null && previousOwner != owner) {
                        followingCurrentPeriod = true
                    }
                    previousOwner = owner
                    // A period fetched for the previous student must not direct a new login's
                    // requests before its own account-scoped metadata has arrived.
                    periods.value = emptyList()
                    repository.observePeriods()
                }.collect { known ->
                    periods.value = known
                    // A foreground recovery may deliver the new period before the next local tick.
                    updateClock(timeProvider.nowDateTime())
                }
        }
        // A foreground recovery runs outside this ViewModel; its complete refresh must replace
        // an old transport error here as well. Authentication alone proves no punch data.
        viewModelScope.launch {
            var appliedSequence = 0L
            var observedOwner: String? = null
            var observedScope: Triple<String, LocalDate, LocalDate>? = null
            var ownerInitialized = false
            combine(repository.refreshUpdates, sessionManager.state, month, periods) { updates, session, selected, _ ->
                val owner = session.accountHashOf(SchoolSystem.STU)
                val (from, to) = punchSpanFor(selected)
                val scope = Triple(selected, from, to)
                if (observedScope != scope) appliedSequence = 0L
                observedScope = scope
                if (ownerInitialized && observedOwner != owner) {
                    appliedSequence = 0L
                    // Initial DataStore hydration may follow the first completed request.
                    if (observedOwner != null && (resultContext?.owner != owner ||
                            resultContext?.accountVersion != sessionManager.accountVersion(SchoolSystem.STU))) {
                        clearResult()
                    }
                }
                observedOwner = owner
                ownerInitialized = true
                latestAttendanceRefresh(updates, owner, sessionManager.accountVersion(SchoolSystem.STU), selected, from, to)
            }.collect { update ->
                if (update != null && update.sequence > appliedSequence) {
                    appliedSequence = update.sequence
                    val context = ResultContext(update.accountHash, update.accountVersion, update.scope.month)
                    val consumed = localCompletion?.takeIf { it.context == context }?.consumedSequence ?: 0L
                    if (update.sequence > consumed) {
                        resultContext = context
                        lastResult.value = update.result
                    }
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
                    updateClock = ::updateClock,
                    prepareMonth = { now ->
                        if (!followingCurrentPeriod) null else {
                            val resolved = repository.resolvePeriod(attendanceDate(now))
                            if (sessionManager.accountVersion(SchoolSystem.STU) != requestedAccountVersion) {
                                AttendanceRefreshResult.Superseded
                            } else when (resolved) {
                                is AttendancePeriodResult.Unavailable -> resolved.reason
                                is AttendancePeriodResult.Found -> {
                                    if (attendanceDate(now) !in resolved.period) {
                                        AttendanceRefreshResult.SchemaChanged("学校考勤周期不包含当前考勤日")
                                    } else {
                                        periods.value = periods.value.filterNot { it.month == resolved.period.month } + resolved.period
                                        selectMonth(resolved.period.month)
                                        null
                                    }
                                }
                            }
                        }
                    }
                )
                // Reading the persisted identity can suspend. Check the request version after
                // that read so a login during it cannot publish the previous account's result.
                val completedOwner = sessionManager.current().accountHashOf(SchoolSystem.STU)
                if (result !is AttendanceRefreshResult.Superseded &&
                    sessionManager.accountVersion(SchoolSystem.STU) == requestedAccountVersion) {
                    val context = ResultContext(completedOwner, requestedAccountVersion, month.value)
                    // A historical refresh can publish its summary success before a current-day
                    // follow-up fails. Those already-produced signals are part of this combined
                    // result, even if their collector has not run yet.
                    val consumed = repository.refreshUpdates.value.values.filter {
                        it.accountHash == context.owner && it.accountVersion == context.accountVersion &&
                            it.scope.month == context.month
                    }.maxOfOrNull { it.sequence } ?: 0L
                    localCompletion = LocalCompletion(context, consumed)
                    resultContext = context
                    lastResult.value = result
                }
            } finally {
                refreshing.value = false
            }
        }
    }

    fun previousMonth() {
        if (refreshing.value) return
        navigateMonth(-1)
    }

    fun nextMonth() {
        if (refreshing.value) return
        navigateMonth(1)
    }

    private fun navigateMonth(delta: Long) {
        val selected = shiftMonth(month.value, delta)
        followingCurrentPeriod = currentAttendancePeriod(
            attendanceDate(timeProvider.nowDateTime()), periods.value
        )?.month == selected
        month.value = selected
        clearResult()
        localCompletion = null
        refresh()
    }

    fun currentMonth() = currentMonthString()

    private fun currentMonthString(): String {
        val date = attendanceDate(timeProvider.nowDateTime())
        return currentAttendancePeriod(date, periods.value)?.month ?: java.time.YearMonth.from(date).toString()
    }

    private fun updateClock(now: java.time.LocalDateTime) {
        val changedDate = attendanceDate(clock.value) != attendanceDate(now)
        clock.value = now
        followCachedPeriod(attendanceDate(now))
        // No calendar arithmetic decides the school month. Unknown boundaries are resolved before
        // the next request; an in-flight refresh repeats after the date/selection changes.
        if (changedDate) refresh()
    }

    private fun followCachedPeriod(date: LocalDate) {
        if (followingCurrentPeriod) currentAttendancePeriod(date, periods.value)?.let { period ->
            if (selectMonth(period.month)) refresh()
        }
    }

    private fun selectMonth(value: String): Boolean {
        if (month.value == value) return false
        month.value = value
        clearResult()
        localCompletion = null
        return true
    }

    private fun clearResult() {
        resultContext = null
        lastResult.value = null
    }

    private fun shiftMonth(value: String, delta: Long): String {
        val year = value.substring(0, 4).toIntOrNull() ?: return value
        val monthValue = value.substring(5).toIntOrNull() ?: return value
        val base = LocalDate.of(year, monthValue, 1).plusMonths(delta)
        return "%04d-%02d".format(base.year, base.monthValue)
    }
}
