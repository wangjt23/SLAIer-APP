package com.slai.campus.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.session.SessionManager
import com.slai.campus.core.session.SessionStore
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.web.AppUrlProvider
import com.slai.campus.core.web.AppUrls
import com.slai.campus.domain.attendance.AttendanceRefreshResult
import com.slai.campus.domain.attendance.AttendanceRepository
import com.slai.campus.domain.attendance.AttendanceRecord
import com.slai.campus.domain.attendance.AttendancePunch
import com.slai.campus.domain.attendance.CurrentAttendance
import com.slai.campus.domain.attendance.DailyAttendance
import com.slai.campus.domain.attendance.PunchPairing
import com.slai.campus.domain.attendance.observeCurrentAttendance
import com.slai.campus.domain.attendance.refreshCurrentAttendance
import com.slai.campus.domain.attendance.attendancePunchRange
import com.slai.campus.domain.attendance.latestAttendanceRefresh
import com.slai.campus.domain.attendance.attendanceDate as attendanceDateAt
import com.slai.campus.domain.schedule.ClassOccurrence
import com.slai.campus.domain.schedule.ScheduleRepository
import com.slai.campus.domain.schedule.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the home screen renders, in one immutable snapshot. */
data class HomeUiState(
    val today: List<ClassOccurrence> = emptyList(),
    val syncState: SyncState? = null,
    val attendance: AttendanceRecord? = null,
    val stuState: SessionState = SessionState.UNKNOWN,
    val urls: AppUrls? = null,
    val attendanceGoalMinutes: Int = 360,
    val nowDateTime: java.time.LocalDateTime = java.time.LocalDateTime.now(),
    val attendanceDate: java.time.LocalDate = attendanceDateAt(nowDateTime),
    val attendanceDaily: DailyAttendance? = null,
    /** 设置里的「首页显示课表」。关掉之后首页只有考勤（高年级无课时用）。 */
    val showTimetable: Boolean = true,
    /** Complete current punch range; presence need not belong to the displayed attendance date. */
    val currentPunches: List<AttendancePunch> = emptyList()
) {
    /**
     * 当前考勤日累计打卡分钟数：优先用闸机流水配对计算（当天汇总字段恒为 0）。
     */
    val attendanceMinutes: Int?
        get() {
            attendance?.takeIf { it.leave == true }?.let {
                return it.displayMinutes(attendanceDaily, nowDateTime)
            }
            val daily = attendanceDaily
            if (daily != null && daily.punches.isNotEmpty()) return daily.minutesAt(nowDateTime)
            return attendance?.checkedInMinutesAt(nowDateTime)
        }

    val attendanceInside: Boolean
        get() {
            if (currentPunches.isNotEmpty()) return PunchPairing.currentlyInsideAt(currentPunches, nowDateTime)
            attendanceDaily?.takeIf { it.punches.isNotEmpty() }?.let {
                return PunchPairing.currentlyInsideAt(it.punches, nowDateTime)
            }
            attendanceDaily?.takeIf { it.sessions.isNotEmpty() }?.let { return it.currentlyInsideAt(nowDateTime) }
            return attendance?.isCurrentlyInsideAt(nowDateTime) == true
        }

    /** At least one successful sync has ever happened, so the cache is meaningful. */
    val hasData: Boolean get() = syncState?.hasEverSucceeded == true

    /** Never synced and nothing cached: show the first-run empty state instead of "no classes". */
    val isFirstRun: Boolean get() = !hasData && today.isEmpty()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val scheduleRepository: ScheduleRepository,
    private val attendanceRepository: AttendanceRepository,
    private val sessionManager: SessionManager,
    private val urlProvider: AppUrlProvider,
    private val sessionStore: SessionStore,
    private val timeProvider: TimeProvider
) : ViewModel() {

    private data class SchedulePart(
        val today: List<ClassOccurrence>,
        val syncState: SyncState
    )

    private val schedulePart = combine(
        scheduleRepository.observeToday(),
        scheduleRepository.observeSyncState()
    ) { today, syncState -> SchedulePart(today, syncState) }

    private val attendanceGoal = sessionStore.attendanceGoalMinutes
    private val clock = MutableStateFlow(timeProvider.nowDateTime())

    private val currentAttendance = attendanceRepository.observeCurrentAttendance(clock)

    /** 考勤日与其数据始终一起切换，避免凌晨时钟先更新、记录仍属于上一日。 */
    private data class LivePart(
        val attendance: CurrentAttendance,
        val now: java.time.LocalDateTime,
        val showTimetable: Boolean
    )

    private val livePart = combine(
        currentAttendance,
        clock,
        sessionStore.showTimetableOnHome
    ) { attendance, now, showTimetable ->
        LivePart(attendance, now, showTimetable)
    }

    val state: StateFlow<HomeUiState> = combine(
        schedulePart,
        sessionManager.state,
        urlProvider.urls,
        attendanceGoal,
        livePart
    ) { part, session, urls, goal, live ->
        HomeUiState(
            today = part.today,
            syncState = part.syncState,
            attendance = live.attendance.record,
            stuState = session.stu,
            urls = urls,
            attendanceGoalMinutes = goal,
            nowDateTime = live.now,
            attendanceDate = live.attendance.date,
            attendanceDaily = PunchPairing.of(
                live.attendance.date,
                live.attendance.punches,
                live.now
            ),
            showTimetable = live.showTimetable,
            currentPunches = live.attendance.punches
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(nowDateTime = clock.value))

    private val _attendanceRefreshing = MutableStateFlow(false)
    val attendanceRefreshing = _attendanceRefreshing.asStateFlow()
    private val _attendanceResult = MutableStateFlow<AttendanceRefreshResult?>(null)
    val attendanceResult = _attendanceResult.asStateFlow()

    init {
        viewModelScope.launch {
            timeProvider.observeDateTime().collect { clock.value = it }
        }
        viewModelScope.launch {
            var appliedSequence = 0L
            var observedOwner: String? = null
            var observedDate: java.time.LocalDate? = null
            var ownerInitialized = false
            combine(attendanceRepository.refreshUpdates, sessionManager.state, clock) { updates, session, now ->
                val owner = session.accountHashOf(SchoolSystem.STU)
                if (ownerInitialized && observedOwner != owner) {
                    appliedSequence = 0L
                    if (observedOwner != null) _attendanceResult.value = null
                }
                observedOwner = owner
                ownerInitialized = true
                val date = attendanceDateAt(now)
                if (observedDate != date) appliedSequence = 0L
                observedDate = date
                val (from, to) = attendancePunchRange(date)
                latestAttendanceRefresh(updates, owner, sessionManager.accountVersion(SchoolSystem.STU),
                    java.time.YearMonth.from(date).toString(), from, to)
            }.collect { update ->
                if (update != null && update.sequence > appliedSequence) {
                    appliedSequence = update.sequence
                    _attendanceResult.value = update.result
                }
            }
        }
    }

    fun refreshAttendance() {
        if (_attendanceRefreshing.value) return
        _attendanceRefreshing.value = true
        viewModelScope.launch {
            try {
                val requestedAccountVersion = sessionManager.accountVersion(SchoolSystem.STU)
                val result = attendanceRepository.refreshCurrentAttendance(timeProvider.nowDateTime())
                if (result !is AttendanceRefreshResult.Superseded &&
                    sessionManager.accountVersion(SchoolSystem.STU) == requestedAccountVersion) {
                    _attendanceResult.value = result
                }
                clock.value = timeProvider.nowDateTime()
            } finally {
                _attendanceRefreshing.value = false
            }
        }
    }
}
