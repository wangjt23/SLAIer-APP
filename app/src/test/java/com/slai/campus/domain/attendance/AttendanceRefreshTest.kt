package com.slai.campus.domain.attendance

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

class AttendanceRefreshTest {
    private class Repository(
        val summary: AttendanceRefreshResult,
        val punches: AttendanceRefreshResult
    ) : AttendanceRepository {
        var punchCalls = 0
        override suspend fun refresh(month: String) = summary
        override suspend fun refreshPunches(from: LocalDate, to: LocalDate): AttendanceRefreshResult {
            punchCalls++
            return punches
        }
        override fun observeDay(date: LocalDate) = flowOf<AttendanceRecord?>(null)
        override fun observeMonth(month: String) = flowOf(AttendanceMonth(month))
        override fun observeSyncState() = flowOf(AttendanceSyncState())
        override fun observePunches(date: LocalDate) = flowOf(emptyList<AttendancePunch>())
        override fun observePunches(from: LocalDate, to: LocalDate) = observePunches(from)
        override fun observePunchSyncState() = observeSyncState()
        override suspend fun cachedMonths() = emptyList<String>()
    }
    private val day = LocalDate.of(2026, 9, 13)
    private val success = AttendanceRefreshResult.Success("2026-09", 13)

    @Test fun `summary success must not hide expired or failed punch refresh`() = runTest {
        listOf(AttendanceRefreshResult.SessionExpired, AttendanceRefreshResult.Unreachable("timeout"),
            AttendanceRefreshResult.SchemaChanged("invalid")).forEach { failure ->
            val repo = Repository(success, failure)
            assertThat(repo.refreshWithPunches("2026-09", day, day)).isEqualTo(failure)
        }
    }
    @Test fun `expired summary stops before another redundant protected request`() = runTest {
        val repo = Repository(AttendanceRefreshResult.SessionExpired, success)
        assertThat(repo.refreshWithPunches("2026-09", day, day)).isEqualTo(AttendanceRefreshResult.SessionExpired)
        assertThat(repo.punchCalls).isEqualTo(0)
    }
    @Test fun `successful punch request does not hide failed summary`() = runTest {
        val failure = AttendanceRefreshResult.ServerError(503)
        assertThat(Repository(failure, success).refreshWithPunches("2026-09", day, day)).isEqualTo(failure)
    }
    @Test fun `both sources must succeed to show sync success`() = runTest {
        assertThat(Repository(success, success).refreshWithPunches("2026-09", day, day)).isEqualTo(success)
    }
}
