package com.slai.campus.feature.attendance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.core.session.SessionManager
import com.slai.campus.core.session.SessionStore
import com.slai.campus.core.web.AppUrlProvider
import com.slai.campus.domain.attendance.AttendanceMonth
import com.slai.campus.domain.attendance.AttendancePeriod
import com.slai.campus.domain.attendance.AttendancePeriodResult
import com.slai.campus.domain.attendance.AttendancePunch
import com.slai.campus.domain.attendance.AttendanceRecord
import com.slai.campus.domain.attendance.AttendanceRefreshResult
import com.slai.campus.domain.attendance.AttendanceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceMonthRolloverViewModelTest {
    private class MutableClock : Clock() {
        var localNow: LocalDateTime = LocalDateTime.of(2026, 10, 1, 4, 59)
        override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
        override fun instant(): Instant = localNow.atZone(zone).toInstant()
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }

    private class ImmediateStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }

    private class Repository(
        private val pending: Map<String, CompletableDeferred<AttendanceRefreshResult>> = emptyMap(),
        val schoolPeriods: List<AttendancePeriod> = (8..12).map { month ->
            val calendar = java.time.YearMonth.of(2026, month)
            AttendancePeriod(calendar.toString(), calendar.atDay(1), calendar.atEndOfMonth())
        },
        cached: List<AttendancePeriod> = schoolPeriods
    ) : AttendanceRepository {
        val requested = mutableListOf<String>()
        val periods = MutableStateFlow(cached)
        var resolutionGate: CompletableDeferred<Unit>? = null
        var periodFailure: AttendanceRefreshResult? = null
        override fun observePeriods() = periods
        override suspend fun resolvePeriod(date: LocalDate): AttendancePeriodResult {
            resolutionGate?.await()
            periodFailure?.let { return AttendancePeriodResult.Unavailable(it) }
            val period = schoolPeriods.singleOrNull { date in it }
                ?: return AttendancePeriodResult.Unavailable(AttendanceRefreshResult.SchemaChanged("no period"))
            periods.value = (periods.value.filterNot { it.month == period.month } + period)
            return AttendancePeriodResult.Found(period)
        }
        override suspend fun periodForMonth(month: String): AttendancePeriodResult = schoolPeriods.firstOrNull { it.month == month }
            ?.let { AttendancePeriodResult.Found(it) }
            ?: AttendancePeriodResult.Unavailable(AttendanceRefreshResult.SchemaChanged("no period"))
        override fun observeDay(date: LocalDate) = flowOf<AttendanceRecord?>(null)
        override fun observeMonth(month: String) = flowOf(AttendanceMonth(month))
        override fun observePunches(from: LocalDate, to: LocalDate) = flowOf(emptyList<AttendancePunch>())
        override suspend fun refresh(month: String): AttendanceRefreshResult {
            requested += month
            return pending[month]?.await() ?: AttendanceRefreshResult.Success(month, 0)
        }
        override suspend fun refreshPunches(from: LocalDate, to: LocalDate) =
            AttendanceRefreshResult.Success("punches", 0)
    }

    private val schoolPeriods = listOf(
        AttendancePeriod("2026-09", LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 27)),
        AttendancePeriod("2026-10", LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 25)),
        AttendancePeriod("2026-11", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 29)),
        AttendancePeriod("2026-12", LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 27))
    )

    private fun TestScope.viewModel(repository: Repository, clock: MutableClock): AttendanceViewModel {
        val store = SessionStore(ImmediateStore())
        return AttendanceViewModel(
            repository, SessionManager(store, emptySet(), backgroundScope), AppUrlProvider(store),
            store, TimeProvider(clock)
        )
    }

    private fun mainTest(block: suspend TestScope.() -> Unit) {
        try {
            runTest {
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                block()
            }
        } finally {
            // Keep Main available while runTest drains cancellation and background collectors.
            Dispatchers.resetMain()
        }
    }

    @Test fun `idle attendance screen automatically fetches the new month at five`() = mainTest {
        val repository = Repository()
        val clock = MutableClock()
        val vm = viewModel(repository, clock)
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()
            assertThat(repository.requested).containsExactly("2026-09")
            assertThat(vm.state.value.month).isEqualTo("2026-09")
            assertThat(vm.state.value.refreshing).isFalse()

            clock.localNow = LocalDateTime.of(2026, 10, 1, 5, 0)
            advanceTimeBy(30_000)
            runCurrent()

            assertThat(repository.requested).containsExactly("2026-09", "2026-10").inOrder()
            assertThat(vm.state.value.month).isEqualTo("2026-10")
            assertThat(vm.state.value.todayDate).isEqualTo(LocalDate.of(2026, 10, 1))
            assertThat(vm.state.value.refreshing).isFalse()
            assertThat(vm.state.value.lastResult).isEqualTo(AttendanceRefreshResult.Success("2026-10", 0))
        } finally {
            vm.viewModelScope.cancel()
            runCurrent()
        }
    }

    @Test fun `old request crossing five keeps new month refreshing until its own result arrives`() = mainTest {
        val previousResponse = CompletableDeferred<AttendanceRefreshResult>()
        val currentResponse = CompletableDeferred<AttendanceRefreshResult>()
        val repository = Repository(mapOf("2026-09" to previousResponse, "2026-10" to currentResponse))
        val clock = MutableClock()
        val vm = viewModel(repository, clock)
        val states = mutableListOf<AttendanceUiState>()
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { states += it } }
            runCurrent()
            assertThat(repository.requested).containsExactly("2026-09")
            assertThat(vm.state.value.refreshing).isTrue()

            clock.localNow = LocalDateTime.of(2026, 10, 1, 5, 0)
            advanceTimeBy(30_000)
            runCurrent()
            assertThat(vm.state.value.month).isEqualTo("2026-10")
            assertThat(repository.requested).containsExactly("2026-09")
            assertThat(vm.state.value.refreshing).isTrue()

            previousResponse.complete(AttendanceRefreshResult.Success("2026-09", 30))
            runCurrent()
            assertThat(repository.requested).containsExactly("2026-09", "2026-10").inOrder()
            assertThat(vm.state.value.month).isEqualTo("2026-10")
            assertThat(vm.state.value.refreshing).isTrue()
            assertThat(vm.state.value.lastResult).isNull()

            val failure = AttendanceRefreshResult.Unreachable("campus unavailable")
            currentResponse.complete(failure)
            runCurrent()
            assertThat(vm.state.value.refreshing).isFalse()
            assertThat(vm.state.value.lastResult).isEqualTo(failure)
            assertThat(states.any {
                it.month == "2026-10" && it.lastResult is AttendanceRefreshResult.Success
            }).isFalse()
        } finally {
            vm.viewModelScope.cancel()
            runCurrent()
        }
    }

    @Test fun `opening on October thirty first selects school November without fetching October first`() = mainTest {
        val repository = Repository(schoolPeriods = schoolPeriods)
        val clock = MutableClock().apply { localNow = LocalDateTime.of(2026, 10, 31, 4, 23) }
        val vm = viewModel(repository, clock)
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()
            assertThat(vm.state.value.month).isEqualTo("2026-11")
            assertThat(vm.state.value.todayDate).isEqualTo(LocalDate.of(2026, 10, 30))
            assertThat(repository.requested).containsExactly("2026-11")
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun `uncached school period is resolved before any monthly ledger request`() = mainTest {
        val repository = Repository(schoolPeriods = schoolPeriods, cached = emptyList())
        val gate = CompletableDeferred<Unit>()
        repository.resolutionGate = gate
        val clock = MutableClock().apply { localNow = LocalDateTime.of(2026, 10, 30, 12, 0) }
        val vm = viewModel(repository, clock)
        val states = mutableListOf<AttendanceUiState>()
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { states += it } }
            runCurrent()
            assertThat(repository.requested).isEmpty()
            assertThat(vm.state.value.refreshing).isTrue()
            gate.complete(Unit)
            runCurrent()
            assertThat(repository.requested).containsExactly("2026-11")
            assertThat(vm.state.value.month).isEqualTo("2026-11")
            assertThat(states.any { it.month == "2026-10" && it.lastResult?.isSuccess == true }).isFalse()
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun `school period rolls from October into November at five on October twenty sixth`() = mainTest {
        val repository = Repository(schoolPeriods = schoolPeriods)
        val clock = MutableClock().apply { localNow = LocalDateTime.of(2026, 10, 26, 4, 59) }
        val vm = viewModel(repository, clock)
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()
            assertThat(vm.state.value.month).isEqualTo("2026-10")
            clock.localNow = LocalDateTime.of(2026, 10, 26, 5, 0)
            advanceTimeBy(30_000)
            runCurrent()
            assertThat(vm.state.value.month).isEqualTo("2026-11")
            assertThat(repository.requested).containsExactly("2026-10", "2026-11").inOrder()
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun `returning after a school period boundary resolves the new period during refresh`() = mainTest {
        val repository = Repository(schoolPeriods = schoolPeriods)
        val clock = MutableClock().apply { localNow = LocalDateTime.of(2026, 10, 25, 20, 0) }
        val vm = viewModel(repository, clock)
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()
            clock.localNow = LocalDateTime.of(2026, 10, 26, 12, 0)
            vm.refresh()
            runCurrent()
            assertThat(vm.state.value.month).isEqualTo("2026-11")
            assertThat(repository.requested).containsExactly("2026-10", "2026-11").inOrder()
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun `manual historical selection is retained when current school period advances`() = mainTest {
        val repository = Repository(schoolPeriods = schoolPeriods)
        val clock = MutableClock().apply { localNow = LocalDateTime.of(2026, 10, 30, 12, 0) }
        val vm = viewModel(repository, clock)
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()
            vm.previousMonth()
            runCurrent()
            assertThat(vm.state.value.month).isEqualTo("2026-10")
            clock.localNow = LocalDateTime.of(2026, 11, 30, 12, 0)
            advanceTimeBy(30_000)
            runCurrent()
            assertThat(vm.state.value.month).isEqualTo("2026-10")
            assertThat(vm.state.value.todayDate).isEqualTo(LocalDate.of(2026, 11, 30))
            assertThat(repository.requested.last()).isEqualTo("2026-12")
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun `old school period request cannot finish the new period refresh`() = mainTest {
        val october = CompletableDeferred<AttendanceRefreshResult>()
        val november = CompletableDeferred<AttendanceRefreshResult>()
        val repository = Repository(mapOf("2026-10" to october, "2026-11" to november), schoolPeriods)
        val clock = MutableClock().apply { localNow = LocalDateTime.of(2026, 10, 26, 4, 59) }
        val vm = viewModel(repository, clock)
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()
            clock.localNow = LocalDateTime.of(2026, 10, 26, 5, 0)
            advanceTimeBy(30_000)
            runCurrent()
            october.complete(AttendanceRefreshResult.Success("2026-10", 28))
            runCurrent()
            assertThat(vm.state.value.month).isEqualTo("2026-11")
            assertThat(vm.state.value.refreshing).isTrue()
            assertThat(vm.state.value.lastResult).isNull()
            november.complete(AttendanceRefreshResult.Success("2026-11", 35))
            runCurrent()
            assertThat(vm.state.value.refreshing).isFalse()
            assertThat(vm.state.value.lastResult).isEqualTo(AttendanceRefreshResult.Success("2026-11", 35))
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun `navigating back to current school month restores automatic following`() = mainTest {
        val repository = Repository(schoolPeriods = schoolPeriods)
        val clock = MutableClock().apply { localNow = LocalDateTime.of(2026, 10, 30, 12, 0) }
        val vm = viewModel(repository, clock)
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()
            vm.previousMonth()
            runCurrent()
            vm.nextMonth()
            runCurrent()
            assertThat(vm.state.value.month).isEqualTo("2026-11")
            clock.localNow = LocalDateTime.of(2026, 11, 30, 12, 0)
            advanceTimeBy(30_000)
            runCurrent()
            assertThat(vm.state.value.month).isEqualTo("2026-12")
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun `period lookup failure cannot report calendar month refresh success`() = mainTest {
        val repository = Repository(schoolPeriods = schoolPeriods, cached = emptyList()).apply {
            periodFailure = AttendanceRefreshResult.Unreachable("school unavailable")
        }
        val clock = MutableClock().apply { localNow = LocalDateTime.of(2026, 10, 30, 12, 0) }
        val vm = viewModel(repository, clock)
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()
            assertThat(repository.requested).isEmpty()
            assertThat(vm.state.value.lastResult).isEqualTo(repository.periodFailure)
            assertThat(vm.state.value.refreshing).isFalse()
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }
}
