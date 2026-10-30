package com.slai.campus.feature.attendance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.core.session.SessionManager
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.session.SessionStore
import com.slai.campus.core.web.AppUrlProvider
import com.slai.campus.domain.attendance.*
import com.slai.campus.domain.schedule.*
import com.slai.campus.feature.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceRefreshFeedbackTest {
    private class ImmediateStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(mutablePreferencesOf(
            stringPreferencesKey("stu_account_hash") to "owner",
            stringPreferencesKey("stu_state") to SessionState.AUTHENTICATED.name
        ))
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class Repository(schoolPeriods: List<AttendancePeriod>) : AttendanceRepository {
        private val results = AttendanceRefreshUpdates()
        override val refreshUpdates = results.state
        val periods = MutableStateFlow(schoolPeriods)
        var periodFailure: AttendanceRefreshResult? = null
        var publishRefreshResults = false
        override fun observePeriods() = periods
        override suspend fun resolvePeriod(date: LocalDate): AttendancePeriodResult {
            periodFailure?.let { return AttendancePeriodResult.Unavailable(it) }
            return currentAttendancePeriod(date, periods.value)?.let { AttendancePeriodResult.Found(it) }
                ?: AttendancePeriodResult.Unavailable(AttendanceRefreshResult.SchemaChanged("no period"))
        }
        override suspend fun periodForMonth(month: String): AttendancePeriodResult = periods.value.singleOrNull { it.month == month }
            ?.let { AttendancePeriodResult.Found(it) }
            ?: AttendancePeriodResult.Unavailable(AttendanceRefreshResult.SchemaChanged("no period"))
        var result: AttendanceRefreshResult = AttendanceRefreshResult.Unreachable("old timeout")
        var pending: CompletableDeferred<AttendanceRefreshResult>? = null
        val pendingByMonth = mutableMapOf<String, CompletableDeferred<AttendanceRefreshResult>>()
        override fun observeDay(date: LocalDate) = flowOf<AttendanceRecord?>(null)
        override fun observeMonth(month: String) = flowOf(AttendanceMonth(month))
        override fun observePunches(from: LocalDate, to: LocalDate) = flowOf(emptyList<AttendancePunch>())
        override suspend fun refresh(month: String): AttendanceRefreshResult {
            val refreshed = pendingByMonth[month]?.await() ?: pending?.await() ?: result
            if (publishRefreshResults) publish(refreshed, month = month)
            return refreshed
        }
        override suspend fun refreshPunches(from: LocalDate, to: LocalDate) = result
        fun publish(result: AttendanceRefreshResult, owner: String = "owner", version: Long = 0,
                    fullMonth: Boolean = true, month: String = "2026-10") {
            val (from, to) = if (fullMonth) attendancePeriodPunchRange(periods.value.single { it.month == month })
                else attendancePunchRange(LocalDate.of(2026, 10, 24))
            results.publish(AttendanceRefreshScope(month, from, to), owner, version, result)
        }
    }

    private class Schedule : ScheduleRepository {
        override fun observeToday() = flowOf(emptyList<ClassOccurrence>())
        override fun observeRange(from: LocalDate, to: LocalDate) = observeToday()
        override fun observeSyncState() = flowOf(SyncState(SchoolSystem.SIS))
        override fun observeLastRefresh() = flowOf<RefreshResult?>(null)
        override fun observeIsRefreshing() = flowOf(false)
        override fun observePhase() = flowOf(RefreshPhase.IDLE)
        override fun observeRefreshTrace() = flowOf(emptyList<String>())
        override suspend fun refresh(reason: RefreshReason) = RefreshResult.Success(0, 0)
    }

    private class Fixture(scope: TestScope, schoolPeriods: List<AttendancePeriod>, instant: String) {
        val repository = Repository(schoolPeriods)
        val store = SessionStore(ImmediateStore())
        val manager = SessionManager(store, emptySet(), scope.backgroundScope)
        private val time = TimeProvider(Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Shanghai")))
        val attendance = AttendanceViewModel(repository, manager, AppUrlProvider(store), store, time)
        val home = HomeViewModel(Schedule(), repository, manager, AppUrlProvider(store), store, time)
        fun close() {
            attendance.viewModelScope.cancel()
            home.viewModelScope.cancel()
        }
    }

    private fun mainTest(
        schoolPeriods: List<AttendancePeriod> = (9..11).map { month ->
            val calendar = java.time.YearMonth.of(2026, month)
            AttendancePeriod(calendar.toString(), calendar.atDay(1), calendar.atEndOfMonth())
        },
        instant: String = "2026-10-24T04:00:00Z",
        block: suspend TestScope.(Fixture) -> Unit
    ) {
        try {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val fixture = Fixture(this, schoolPeriods, instant)
                try {
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.attendance.state.collect {} }
                    runCurrent()
                    block(fixture)
                } finally {
                    fixture.close()
                    runCurrent()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `external full refresh clears stale errors on both existing screens`() = mainTest { f ->
        f.home.refreshAttendance()
        runCurrent()
        assertThat(f.attendance.state.value.lastResult).isInstanceOf(AttendanceRefreshResult.Unreachable::class.java)
        assertThat(f.home.attendanceResult.value).isInstanceOf(AttendanceRefreshResult.Unreachable::class.java)
        val success = AttendanceRefreshResult.Success("2026-10", 24)
        f.repository.publish(success)
        runCurrent()
        assertThat(f.attendance.state.value.lastResult).isEqualTo(success)
        assertThat(f.home.attendanceResult.value).isEqualTo(success)
    }

    @Test fun `authentication alone does not hide an actual punch failure`() = mainTest { f ->
        val failure = AttendanceRefreshResult.SchemaChanged("punch schema")
        f.repository.publish(failure)
        runCurrent()
        f.manager.set(SchoolSystem.STU, SessionState.AUTHENTICATED)
        runCurrent()
        assertThat(f.attendance.state.value.lastResult).isEqualTo(failure)
        assertThat(f.home.attendanceResult.value).isEqualTo(failure)
    }

    @Test fun `current day success cannot hide whole-month punch failure`() = mainTest { f ->
        val failure = AttendanceRefreshResult.SchemaChanged("missing historical page")
        f.repository.publish(failure)
        runCurrent()
        val success = AttendanceRefreshResult.Success("2026-10", 24)
        f.repository.publish(success, fullMonth = false)
        runCurrent()
        assertThat(f.attendance.state.value.lastResult).isEqualTo(failure)
        assertThat(f.home.attendanceResult.value).isEqualTo(success)
    }

    @Test fun `old login result cannot overwrite success after reauthentication`() = mainTest { f ->
        f.manager.beginLogin(SchoolSystem.STU)
        f.manager.withAccount(SchoolSystem.STU, "owner", 1) { Unit }
        val success = AttendanceRefreshResult.Success("2026-10", 24)
        f.repository.publish(success, version = 1)
        runCurrent()
        f.repository.publish(AttendanceRefreshResult.Unreachable("old failed request"), version = 0)
        runCurrent()
        assertThat(f.attendance.state.value.lastResult).isEqualTo(success)
        assertThat(f.home.attendanceResult.value).isEqualTo(success)
    }

    @Test fun `another account result cannot replace current account error`() = mainTest { f ->
        val failure = AttendanceRefreshResult.ServerError(503)
        f.repository.publish(failure)
        runCurrent()
        f.repository.publish(AttendanceRefreshResult.Success("2026-10", 24), owner = "other")
        runCurrent()
        assertThat(f.attendance.state.value.lastResult).isEqualTo(failure)
    }

    @Test fun `switching to an older month applies its retained result while refresh is pending`() = mainTest { f ->
        val historicalFailure = AttendanceRefreshResult.ServerError(503)
        f.repository.publish(historicalFailure, month = "2026-09")
        f.repository.publish(AttendanceRefreshResult.Success("2026-10", 24))
        runCurrent()
        f.repository.pending = CompletableDeferred()
        f.attendance.previousMonth()
        runCurrent()
        assertThat(f.attendance.state.value.month).isEqualTo("2026-09")
        assertThat(f.attendance.state.value.refreshing).isTrue()
        assertThat(f.attendance.state.value.lastResult).isEqualTo(historicalFailure)
    }

    @Test fun `old local completion cannot overwrite shared success after same-account manual login`() = mainTest { f ->
        val oldResponse = CompletableDeferred<AttendanceRefreshResult>()
        f.repository.pending = oldResponse
        f.attendance.refresh()
        f.home.refreshAttendance()
        runCurrent()
        f.manager.beginLogin(SchoolSystem.STU)
        f.manager.withAccount(SchoolSystem.STU, "owner", 1) { Unit }
        val success = AttendanceRefreshResult.Success("2026-10", 24)
        f.repository.publish(success, version = 1)
        runCurrent()
        oldResponse.complete(AttendanceRefreshResult.Unreachable("old request timed out"))
        runCurrent()
        assertThat(f.attendance.state.value.lastResult).isEqualTo(success)
        assertThat(f.home.attendanceResult.value).isEqualTo(success)
    }

    @Test fun `old local completion cannot replace the new account shared result`() = mainTest { f ->
        val oldResponse = CompletableDeferred<AttendanceRefreshResult>()
        f.repository.pending = oldResponse
        f.attendance.refresh()
        f.home.refreshAttendance()
        runCurrent()
        f.manager.beginLogin(SchoolSystem.STU)
        f.manager.withAccount(SchoolSystem.STU, "new-owner", 1) { Unit }
        val success = AttendanceRefreshResult.Success("2026-10", 24)
        f.repository.publish(success, owner = "new-owner", version = 2)
        runCurrent()
        oldResponse.complete(AttendanceRefreshResult.ServerError(503))
        runCurrent()
        assertThat(f.attendance.state.value.lastResult).isEqualTo(success)
        assertThat(f.home.attendanceResult.value).isEqualTo(success)
    }

    @Test fun `historical result before account change cannot escape through current-day follow-up`() = mainTest { f ->
        val currentDay = CompletableDeferred<AttendanceRefreshResult>()
        f.repository.pendingByMonth["2026-10"] = currentDay
        f.repository.result = AttendanceRefreshResult.SchemaChanged("old-owner historical response")
        f.attendance.previousMonth()
        runCurrent()
        assertThat(f.attendance.state.value.month).isEqualTo("2026-09")
        assertThat(f.attendance.state.value.refreshing).isTrue()
        f.manager.beginLogin(SchoolSystem.STU)
        f.manager.withAccount(SchoolSystem.STU, "new-owner", 1) { Unit }
        val success = AttendanceRefreshResult.Success("2026-10", 24)
        f.repository.publish(success, owner = "new-owner", version = 2)
        runCurrent()
        // The helper now returns its previously saved historical failure after a successful
        // current-day query. The outer ViewModel must still reject that old-account result.
        f.repository.result = AttendanceRefreshResult.Success("2026-10", 24)
        currentDay.complete(f.repository.result)
        runCurrent()
        assertThat(f.attendance.state.value.month).isEqualTo("2026-10")
        assertThat(f.attendance.state.value.refreshing).isFalse()
        assertThat(f.attendance.state.value.lastResult).isEqualTo(success)
    }

    @Test fun `November recovery on an October attendance day clears errors on both screens`() = mainTest(
        schoolPeriods = listOf(
            AttendancePeriod("2026-10", LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 25)),
            AttendancePeriod("2026-11", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 29))
        ),
        instant = "2026-10-30T04:00:00Z"
    ) { f ->
        f.home.refreshAttendance()
        runCurrent()
        assertThat(f.attendance.state.value.month).isEqualTo("2026-11")
        assertThat(f.attendance.state.value.lastResult).isInstanceOf(AttendanceRefreshResult.Unreachable::class.java)
        assertThat(f.home.attendanceResult.value).isInstanceOf(AttendanceRefreshResult.Unreachable::class.java)
        val recovered = AttendanceRefreshResult.Success("2026-11", 35)
        f.repository.publish(recovered, month = "2026-11")
        runCurrent()
        assertThat(f.attendance.state.value.lastResult).isEqualTo(recovered)
        assertThat(f.home.attendanceResult.value).isEqualTo(recovered)
    }

    @Test fun `late selected-month success cannot hide a current-period lookup failure`() = mainTest { f ->
        val failure = AttendanceRefreshResult.Unreachable("current period unavailable")
        f.repository.result = AttendanceRefreshResult.Success("2026-09", 30)
        f.repository.periodFailure = failure
        f.repository.publishRefreshResults = true
        f.attendance.previousMonth()
        runCurrent()
        assertThat(f.attendance.state.value.month).isEqualTo("2026-09")
        assertThat(f.attendance.state.value.lastResult).isEqualTo(failure)
        assertThat(f.attendance.state.value.refreshing).isFalse()

        // A later independent recovery is still allowed to replace the combined local failure.
        val recovered = AttendanceRefreshResult.Success("2026-09", 30)
        f.repository.publish(recovered, month = "2026-09")
        runCurrent()
        assertThat(f.attendance.state.value.lastResult).isEqualTo(recovered)
    }
}
