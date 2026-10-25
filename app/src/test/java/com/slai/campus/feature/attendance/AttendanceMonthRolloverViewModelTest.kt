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
        private val pending: Map<String, CompletableDeferred<AttendanceRefreshResult>> = emptyMap()
    ) : AttendanceRepository {
        val requested = mutableListOf<String>()
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
}
