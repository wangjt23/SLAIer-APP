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
import com.slai.campus.domain.attendance.AttendanceRefreshResult
import com.slai.campus.domain.attendance.refreshWithPunches
import com.slai.campus.domain.attendance.AttendanceRepository
import com.slai.campus.domain.attendance.AttendancePunch
import com.slai.campus.domain.attendance.AttendanceRecord
import com.slai.campus.domain.attendance.DailyAttendance
import com.slai.campus.domain.attendance.PunchPairing
import com.slai.campus.domain.schedule.ClassOccurrence
import com.slai.campus.domain.schedule.ScheduleRepository
import com.slai.campus.domain.schedule.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
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
    val attendanceDaily: DailyAttendance? = null,
    /** 设置里的「首页显示课表」。关掉之后首页只有考勤（高年级无课时用）。 */
    val showTimetable: Boolean = true
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

    /** At least one successful sync has ever happened, so the cache is meaningful. */
    val hasData: Boolean get() = syncState?.hasEverSucceeded == true

    /** Never synced and nothing cached: show the first-run empty state instead of "no classes". */
    val isFirstRun: Boolean get() = !hasData && today.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
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
        val syncState: SyncState,
        val attendance: AttendanceRecord?
    )

    private val schedulePart = combine(
        scheduleRepository.observeToday(),
        scheduleRepository.observeSyncState(),
        timeProvider.observeDate().flatMapLatest { attendanceRepository.observeDay(it) }
    ) { today, syncState, attendance -> SchedulePart(today, syncState, attendance) }

    private val attendanceGoal = sessionStore.attendanceGoalMinutes
    private val clock = MutableStateFlow(timeProvider.nowDateTime())

    private val todayPunches = timeProvider.observeDate().flatMapLatest { attendanceRepository.observePunches(it) }

    /** 打卡流水 + 时钟 + 「首页显示课表」开关；凑在一起只是因为 combine 最多收 5 个流。 */
    private data class LivePart(
        val punches: List<AttendancePunch>,
        val now: java.time.LocalDateTime,
        val showTimetable: Boolean
    )

    private val livePart = combine(
        todayPunches,
        clock,
        sessionStore.showTimetableOnHome
    ) { punches, now, showTimetable ->
        LivePart(punches, now, showTimetable)
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
            attendance = part.attendance,
            stuState = session.stu,
            urls = urls,
            attendanceGoalMinutes = goal,
            nowDateTime = live.now,
            attendanceDaily = PunchPairing.of(
                live.now.toLocalDate(),
                live.punches,
                live.now
            ),
            showTimetable = live.showTimetable
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                clock.value = timeProvider.nowDateTime()
            }
        }
    }

    private val _attendanceRefreshing = MutableStateFlow(false)
    val attendanceRefreshing = _attendanceRefreshing.asStateFlow()
    private val _attendanceResult = MutableStateFlow<AttendanceRefreshResult?>(null)
    val attendanceResult = combine(_attendanceResult, sessionManager.state) { result, session ->
        result.takeUnless { it is AttendanceRefreshResult.SessionExpired && session.stu == SessionState.AUTHENTICATED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refreshAttendance() {
        if (_attendanceRefreshing.value) return
        _attendanceRefreshing.value = true
        viewModelScope.launch {
            try {
                val today = timeProvider.today()
                _attendanceResult.value = attendanceRepository.refreshWithPunches(
                    com.slai.campus.data.stu.StuAttendanceDataSource.monthOf(today), today, today
                )
                clock.value = timeProvider.nowDateTime()
            } finally {
                _attendanceRefreshing.value = false
            }
        }
    }
}
