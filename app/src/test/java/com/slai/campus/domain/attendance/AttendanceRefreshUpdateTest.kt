package com.slai.campus.domain.attendance

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class AttendanceRefreshUpdateTest {
    private val month = "2026-10"
    private val date = LocalDate.of(2026, 10, 24)
    private val monthRange = monthPunchRange(YearMonth.parse(month))
    private val scope = AttendanceRefreshScope(month, monthRange.first, monthRange.second)
    private val success = AttendanceRefreshResult.Success(month, 24)

    @Test fun `complete success replaces an old transport failure for the same context`() {
        val updates = AttendanceRefreshUpdates()
        updates.publish(scope, "owner", 2, AttendanceRefreshResult.Unreachable("timeout"))
        updates.publish(scope, "owner", 2, success)
        val latest = latestAttendanceRefresh(updates.state.value, "owner", 2, month, scope.from, scope.to)
        assertThat(latest?.result).isEqualTo(success)
        assertThat(latest?.sequence).isEqualTo(2)
    }

    @Test fun `a day-only refresh cannot clear a failed whole-month refresh`() {
        val updates = AttendanceRefreshUpdates()
        val failure = AttendanceRefreshResult.SchemaChanged("bad historical punches")
        updates.publish(scope, "owner", 2, failure)
        val (from, to) = attendancePunchRange(date)
        updates.publish(AttendanceRefreshScope(month, from, to), "owner", 2, success)
        assertThat(latestAttendanceRefresh(updates.state.value, "owner", 2, month, scope.from, scope.to)?.result)
            .isEqualTo(failure)
        assertThat(latestAttendanceRefresh(updates.state.value, "owner", 2, month, from, to)?.result)
            .isEqualTo(success)
    }

    @Test fun `another month update retains the selected month result for later collectors`() {
        val updates = AttendanceRefreshUpdates()
        updates.publish(scope, "owner", 2, success)
        val september = AttendanceRefreshScope("2026-09", scope.from.minusMonths(1), scope.to.minusMonths(1))
        updates.publish(september, "owner", 2, AttendanceRefreshResult.ServerError(503))
        assertThat(latestAttendanceRefresh(updates.state.value, "owner", 2, month, scope.from, scope.to)?.result)
            .isEqualTo(success)
    }

    @Test fun `updates from another account or older login do not match`() {
        val updates = AttendanceRefreshUpdates()
        updates.publish(scope, "old-owner", 2, success)
        assertThat(latestAttendanceRefresh(updates.state.value, "new-owner", 2, month, scope.from, scope.to)).isNull()
        assertThat(latestAttendanceRefresh(updates.state.value, "old-owner", 3, month, scope.from, scope.to)).isNull()
    }

    @Test fun `superseded request cannot replace newer success`() {
        val updates = AttendanceRefreshUpdates()
        updates.publish(scope, "owner", 2, success)
        updates.publish(scope, "owner", 2, AttendanceRefreshResult.Superseded)
        assertThat(updates.state.value.getValue(scope).result).isEqualTo(success)
        assertThat(updates.state.value.getValue(scope).sequence).isEqualTo(1)
    }

    @Test fun `request metadata remains bounded`() {
        val updates = AttendanceRefreshUpdates()
        repeat(30) { offset ->
            val from = date.minusDays(offset.toLong())
            updates.publish(AttendanceRefreshScope(month, from, from.plusDays(1)), "owner", 2, success)
        }
        assertThat(updates.state.value).hasSize(24)
        assertThat(updates.state.value.values.minOf { it.sequence }).isEqualTo(7)
    }

    @Test fun `user expiration remains distinguishable from a recovery refresh expiration`() {
        val updates = AttendanceRefreshUpdates()
        updates.publish(scope, "owner", 2, AttendanceRefreshResult.SessionExpired)
        assertThat(updates.state.value.getValue(scope).origin).isEqualTo(AttendanceRefreshOrigin.USER)
        updates.publish(scope, "owner", 2, AttendanceRefreshResult.SessionExpired, AttendanceRefreshOrigin.SESSION_RECOVERY)
        assertThat(updates.state.value).hasSize(1)
        val latest = latestAttendanceRefresh(updates.state.value, "owner", 2, month, scope.from, scope.to)
        assertThat(latest?.origin).isEqualTo(AttendanceRefreshOrigin.SESSION_RECOVERY)
        // The recovery failure is still visible; only the recovery coordinator suppresses reentry.
        assertThat(latest?.result).isEqualTo(AttendanceRefreshResult.SessionExpired)
    }

    @Test fun `current attendance defaults to user origin`() = runTest {
        val repository = OriginRepository()
        repository.refreshCurrentAttendance(date.atTime(12, 0))
        assertThat(repository.origins).containsExactly(AttendanceRefreshOrigin.USER)
    }

    @Test fun `historical month follow-up preserves the recovery origin on current day refresh`() = runTest {
        val repository = OriginRepository()
        repository.refreshMonthWithCurrentDay("2026-09", date.atTime(12, 0), AttendanceRefreshOrigin.SESSION_RECOVERY)
        assertThat(repository.months).containsExactly("2026-09", "2026-10").inOrder()
        assertThat(repository.origins).containsExactly(AttendanceRefreshOrigin.SESSION_RECOVERY,
            AttendanceRefreshOrigin.SESSION_RECOVERY).inOrder()
    }

    private class OriginRepository : AttendanceRepository {
        val origins = mutableListOf<AttendanceRefreshOrigin>()
        val months = mutableListOf<String>()
        override fun observePeriods() = flowOf(emptyList<AttendancePeriod>())
        override suspend fun resolvePeriod(date: LocalDate) = periodForMonth(YearMonth.from(date).toString())
        override suspend fun periodForMonth(month: String): AttendancePeriodResult {
            val calendar = YearMonth.parse(month)
            return AttendancePeriodResult.Found(AttendancePeriod(month, calendar.atDay(1), calendar.atEndOfMonth()))
        }
        override fun observeDay(date: LocalDate) = flowOf<AttendanceRecord?>(null)
        override fun observeMonth(month: String) = flowOf(AttendanceMonth(month))
        override fun observePunches(from: LocalDate, to: LocalDate) = flowOf(emptyList<AttendancePunch>())
        override suspend fun refresh(month: String) = AttendanceRefreshResult.Success(month, 0)
        override suspend fun refreshPunches(from: LocalDate, to: LocalDate) = AttendanceRefreshResult.Success(to.toString(), 0)
        override suspend fun refreshWithPunches(month: String, from: LocalDate, to: LocalDate,
                                              origin: AttendanceRefreshOrigin): AttendanceRefreshResult {
            months += month
            origins += origin
            return AttendanceRefreshResult.Success(month, 0)
        }
    }
}
