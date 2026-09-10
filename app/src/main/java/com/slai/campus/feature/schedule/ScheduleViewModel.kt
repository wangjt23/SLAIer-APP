package com.slai.campus.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.core.session.SessionManager
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.session.SessionStore
import com.slai.campus.domain.schedule.ClassOccurrence
import com.slai.campus.domain.schedule.RefreshResult
import com.slai.campus.domain.schedule.ScheduleRepository
import com.slai.campus.domain.schedule.Semester
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/** One day of the selected week. */
data class DaySection(
    val date: LocalDate,
    val classes: List<ClassOccurrence>
) {
    val isToday: Boolean get() = date == LocalDate.now()
}

data class WeekUiState(
    val weekIndex: Int? = null,
    val monday: LocalDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)),
    val days: List<DaySection> = emptyList(),
    val anchored: Boolean = true,
    val hasAnyData: Boolean = false,
    val lastRefresh: RefreshResult? = null,
    val sisState: SessionState = SessionState.UNKNOWN
) {

    /**
     * The school session is gone.
     *
     * Both sources are consulted on purpose: the persisted state machine may lag behind (a refresh
     * that just got a 901 knows before the next probe does), and the probe may lag behind a fresh
     * `RefreshResult.SessionExpired`.
     */
    val needsLogin: Boolean
        get() = sisState == SessionState.EXPIRED ||
            sisState == SessionState.NEEDS_LOGIN ||
            lastRefresh is RefreshResult.SessionExpired ||
            (lastRefresh is RefreshResult.ServerError && lastRefresh.code == 901)

    /**
     * The reason the timetable is empty.
     *
     * Returned as a **structured value, not a sentence**: the ViewModel has no Context, so it cannot
     * call `stringResource`. Baking the Chinese text in here is exactly what made the app
     * monolingual — the UI now resolves it per locale via `EmptyReason.text()`.
     *
     * ("暂无课表数据" on its own was the original problem: a dead session, a changed endpoint and a
     * genuinely empty semester all rendered identically, so there was nothing to act on.)
     */
    val emptyReason: EmptyReason
        get() = when {
            lastRefresh is RefreshResult.SessionExpired -> EmptyReason.SessionExpired
            sisState == SessionState.NEEDS_LOGIN -> EmptyReason.NeverSignedIn
            lastRefresh is RefreshResult.Offline -> EmptyReason.OfflineNoCache
            lastRefresh is RefreshResult.ServerError ->
                if ((lastRefresh as RefreshResult.ServerError).code == 901) {
                    EmptyReason.SessionExpired
                } else {
                    EmptyReason.ServerError((lastRefresh as RefreshResult.ServerError).code)
                }
            lastRefresh is RefreshResult.SchemaChanged ->
                EmptyReason.SchemaChanged((lastRefresh as RefreshResult.SchemaChanged).reason)
            lastRefresh is RefreshResult.Failed ->
                EmptyReason.Failed((lastRefresh as RefreshResult.Failed).reason)
            else -> EmptyReason.NeverSynced
        }
}

/** Why the week view has nothing to show. Resolved to text in the UI, per locale. */
sealed interface EmptyReason {
    /** The 901 / login-redirect case. */
    data object SessionExpired : EmptyReason
    data object NeverSignedIn : EmptyReason
    data object OfflineNoCache : EmptyReason
    data class ServerError(val code: Int) : EmptyReason

    /** [detail] comes from the data layer and stays as the school's/our diagnostic text. */
    data class SchemaChanged(val detail: String) : EmptyReason
    data class Failed(val detail: String) : EmptyReason
    data object NeverSynced : EmptyReason
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val scheduleRepository: ScheduleRepository,
    private val sessionManager: SessionManager,
    private val sessionStore: SessionStore,
    private val timeProvider: TimeProvider
) : ViewModel() {

    /** 0 = current week, -1 = previous week, +1 = next week. */
    private val weekOffset = MutableStateFlow(0)
    val offset: StateFlow<Int> = weekOffset.asStateFlow()

    private val anchor = MutableStateFlow<Pair<LocalDate?, Boolean>>(null to false)

    init {
        viewModelScope.launch {
            anchor.value = sessionStore.semesterAnchor()
            sessionManager.hydrate()
        }
    }

    private val mondayOfSelectedWeek: StateFlow<LocalDate> = weekOffset
        .let { offsets ->
            combine(offsets, anchor) { offset, _ ->
                timeProvider.today()
                    .with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                    .plusWeeks(offset.toLong())
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, timeProvider.today())

    private val selectedWeekClasses =
        mondayOfSelectedWeek.flatMapLatest { monday ->
            scheduleRepository.observeRange(monday, monday.plusDays(6)).map { classes -> monday to classes }
        }

    val state: StateFlow<WeekUiState> = combine(
        selectedWeekClasses,
        anchor,
        scheduleRepository.observeLastRefresh(),
        sessionManager.state
    ) { (monday, classes), (anchorDate, confirmed), lastRefresh, session ->
        val semester = Semester(firstWeekMonday = anchorDate)
        val weekIndex = semester.weekIndexOf(monday)
        val byDate = classes.groupBy { it.date }
        WeekUiState(
            weekIndex = weekIndex,
            monday = monday,
            days = (0L..6L).map { dayOffset ->
                val date = monday.plusDays(dayOffset)
                DaySection(date, byDate[date].orEmpty())
            },
            anchored = anchorDate != null,
            hasAnyData = classes.isNotEmpty(),
            lastRefresh = lastRefresh,
            sisState = session.sis
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeekUiState())

    fun previousWeek() {
        weekOffset.value -= 1
    }

    fun nextWeek() {
        weekOffset.value += 1
    }

    fun currentWeek() {
        weekOffset.value = 0
    }
}
