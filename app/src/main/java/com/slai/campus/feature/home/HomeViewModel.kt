package com.slai.campus.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.core.session.SessionManager
import com.slai.campus.core.session.SessionStore
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.web.AppUrlProvider
import com.slai.campus.core.web.AppUrls
import com.slai.campus.domain.attendance.AttendanceRepository
import com.slai.campus.domain.attendance.AttendancePunch
import com.slai.campus.domain.attendance.AttendanceRecord
import com.slai.campus.domain.attendance.DailyAttendance
import com.slai.campus.domain.attendance.PunchPairing
import com.slai.campus.domain.schedule.ClassOccurrence
import com.slai.campus.domain.schedule.RefreshPhase
import com.slai.campus.domain.schedule.RefreshReason
import com.slai.campus.domain.schedule.RefreshResult
import com.slai.campus.domain.schedule.ScheduleRepository
import com.slai.campus.domain.schedule.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the home screen renders, in one immutable snapshot. */
data class HomeUiState(
    val today: List<ClassOccurrence> = emptyList(),
    val syncState: SyncState? = null,
    val isRefreshing: Boolean = false,
    val lastRefresh: RefreshResult? = null,
    val attendance: AttendanceRecord? = null,
    val sisState: SessionState = SessionState.UNKNOWN,
    val stuState: SessionState = SessionState.UNKNOWN,
    val urls: AppUrls? = null,
    val phase: RefreshPhase = RefreshPhase.IDLE,
    val attendanceGoalMinutes: Int = 360,
    val nowDateTime: java.time.LocalDateTime = java.time.LocalDateTime.now(),
    val attendanceDaily: DailyAttendance? = null
) {
    /**
     * 今日累计打卡分钟数：优先用闸机流水配对计算（当天汇总字段恒为 0）。
     */
    val attendanceMinutes: Int?
        get() {
            val daily = attendanceDaily
            if (daily != null && daily.punches.isNotEmpty()) return daily.minutesAt(nowDateTime)
            return attendance?.checkedInMinutes(nowDateTime.toLocalTime())
        }

    val attendanceInside: Boolean
        get() = attendanceDaily?.currentlyInsideAt(nowDateTime) == true || attendance?.isCurrentlyInside == true

    /** The session is gone: the screen must say "需要重新登录", never "今天没有课". */
    val needsLogin: Boolean
        get() = sisState == SessionState.EXPIRED || sisState == SessionState.NEEDS_LOGIN

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

    private val _refreshing = MutableStateFlow(false)
    val manualRefreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private data class SchedulePart(
        val today: List<ClassOccurrence>,
        val syncState: SyncState,
        val isRefreshing: Boolean,
        val lastRefresh: RefreshResult?,
        val attendance: AttendanceRecord?,
        val phase: RefreshPhase
    )

    private data class ScheduleCore(
        val today: List<ClassOccurrence>,
        val syncState: SyncState,
        val isRefreshing: Boolean,
        val lastRefresh: RefreshResult?,
        val attendance: AttendanceRecord?
    )

    private val scheduleCore = combine(
        scheduleRepository.observeToday(),
        scheduleRepository.observeSyncState(),
        scheduleRepository.observeIsRefreshing(),
        scheduleRepository.observeLastRefresh(),
        attendanceRepository.observeDay(timeProvider.today())
    ) { today, syncState, isRefreshing, lastRefresh, attendance ->
        ScheduleCore(today, syncState, isRefreshing, lastRefresh, attendance)
    }

    // kotlinx.coroutines.combine has typed overloads only up to 5 flows, so the phase is folded in
    // as a second step.
    private val schedulePart = combine(
        scheduleCore,
        scheduleRepository.observePhase()
    ) { core, phase ->
        SchedulePart(core.today, core.syncState, core.isRefreshing, core.lastRefresh, core.attendance, phase)
    }

    private val attendanceGoal = sessionStore.attendanceGoalMinutes
    private val clock = MutableStateFlow(java.time.LocalDateTime.now())

    private val todayPunches = attendanceRepository.observePunches(timeProvider.today())

    val state: StateFlow<HomeUiState> = combine(
        schedulePart,
        sessionManager.state,
        urlProvider.urls,
        attendanceGoal,
        combine(todayPunches, clock) { punches, now -> punches to now }
    ) { part, session, urls, goal, punchesAndNow ->
        HomeUiState(
            today = part.today,
            syncState = part.syncState,
            isRefreshing = part.isRefreshing,
            lastRefresh = part.lastRefresh,
            attendance = part.attendance,
            sisState = session.sis,
            stuState = session.stu,
            urls = urls,
            phase = part.phase,
            attendanceGoalMinutes = goal,
            nowDateTime = punchesAndNow.second,
            attendanceDaily = PunchPairing.of(
                timeProvider.today(),
                punchesAndNow.first,
                punchesAndNow.second
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                clock.value = java.time.LocalDateTime.now()
            }
        }
        viewModelScope.launch {
            // A silent refresh at app start; the UI renders the cache while this runs.
            if (sessionManager.stateOf(SchoolSystem.SIS) == SessionState.AUTHENTICATED) {
                scheduleRepository.refresh(RefreshReason.APP_START)
            }
        }
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                scheduleRepository.refresh(RefreshReason.MANUAL)
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun refreshAttendance() {
        viewModelScope.launch {
            val today = timeProvider.today()
            attendanceRepository.refresh(com.slai.campus.data.stu.StuAttendanceDataSource.monthOf(today))
            attendanceRepository.refreshPunches(today, today)
        }
    }
}
