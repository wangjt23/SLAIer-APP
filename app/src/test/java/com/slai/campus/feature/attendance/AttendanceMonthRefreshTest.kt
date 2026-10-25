package com.slai.campus.feature.attendance

import com.google.common.truth.Truth.assertThat
import com.slai.campus.domain.attendance.AttendanceRefreshResult
import com.slai.campus.domain.attendance.attendanceDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceMonthRefreshTest {
    private val beforeRollover = LocalDateTime.of(2026, 10, 1, 4, 59)
    private val afterRollover = LocalDateTime.of(2026, 10, 1, 5, 0)

    @Test fun `old month success cannot finish until the newly selected month finishes`() = runTest {
        var selected = "2026-09"
        var now = beforeRollover
        val responses = mapOf(
            "2026-09" to CompletableDeferred<AttendanceRefreshResult>(),
            "2026-10" to CompletableDeferred<AttendanceRefreshResult>()
        )
        val requested = mutableListOf<String>()
        val refresh = async {
            refreshSelectedAttendanceMonth(
                selectedMonth = { selected },
                now = { now },
                refreshMonth = { month, _ -> requested += month; responses.getValue(month).await() },
                updateClock = {}
            )
        }
        runCurrent()
        selected = "2026-10"
        now = afterRollover
        responses.getValue("2026-09").complete(AttendanceRefreshResult.Success("2026-09", 30))
        runCurrent()
        assertThat(requested).containsExactly("2026-09", "2026-10").inOrder()
        assertThat(refresh.isCompleted).isFalse()

        val latest = AttendanceRefreshResult.Success("2026-10", 1)
        responses.getValue("2026-10").complete(latest)
        assertThat(refresh.await()).isEqualTo(latest)
    }

    @Test fun `new month failure is reported instead of stale success`() = runTest {
        var selected = "2026-09"
        val oldResponse = CompletableDeferred<AttendanceRefreshResult>()
        val failure = AttendanceRefreshResult.Unreachable("campus unavailable")
        val refresh = async {
            refreshSelectedAttendanceMonth(
                selectedMonth = { selected },
                now = { afterRollover },
                refreshMonth = { month, _ -> if (month == "2026-09") oldResponse.await() else failure },
                updateClock = {}
            )
        }
        runCurrent()
        selected = "2026-10"
        oldResponse.complete(AttendanceRefreshResult.Success("2026-09", 30))
        assertThat(refresh.await()).isEqualTo(failure)
    }

    @Test fun `multiple selection changes return only the final months result`() = runTest {
        var selected = "2026-08"
        val responses = listOf("2026-08", "2026-09", "2026-10")
            .associateWith { CompletableDeferred<AttendanceRefreshResult>() }
        val requested = mutableListOf<String>()
        val refresh = async {
            refreshSelectedAttendanceMonth(
                selectedMonth = { selected },
                now = { afterRollover },
                refreshMonth = { month, _ -> requested += month; responses.getValue(month).await() },
                updateClock = {}
            )
        }
        runCurrent()
        selected = "2026-09"
        responses.getValue("2026-08").complete(AttendanceRefreshResult.Success("2026-08", 31))
        runCurrent()
        selected = "2026-10"
        responses.getValue("2026-09").complete(AttendanceRefreshResult.Failed("stale failure"))
        runCurrent()
        assertThat(refresh.isCompleted).isFalse()
        val latest = AttendanceRefreshResult.Success("2026-10", 1)
        responses.getValue("2026-10").complete(latest)
        assertThat(refresh.await()).isEqualTo(latest)
        assertThat(requested).containsExactly("2026-08", "2026-09", "2026-10").inOrder()
    }

    @Test fun `completion clock rollover also refreshes the new month`() = runTest {
        var selected = "2026-09"
        var now = beforeRollover
        val oldResponse = CompletableDeferred<AttendanceRefreshResult>()
        val requested = mutableListOf<Pair<String, LocalDateTime>>()
        val observedClock = mutableListOf<LocalDateTime>()
        val refresh = async {
            refreshSelectedAttendanceMonth(
                selectedMonth = { selected },
                now = { now },
                refreshMonth = { month, startedAt ->
                    requested += month to startedAt
                    if (month == "2026-09") oldResponse.await()
                    else AttendanceRefreshResult.Success(month, 1)
                },
                updateClock = { clock ->
                    observedClock += clock
                    selected = YearMonth.from(attendanceDate(clock)).toString()
                }
            )
        }
        runCurrent()
        now = afterRollover
        oldResponse.complete(AttendanceRefreshResult.Success("2026-09", 30))
        assertThat(refresh.await()).isEqualTo(AttendanceRefreshResult.Success("2026-10", 1))
        assertThat(requested).containsExactly(
            "2026-09" to beforeRollover, "2026-10" to afterRollover
        ).inOrder()
        assertThat(observedClock).containsExactly(afterRollover, afterRollover)
    }

    @Test fun `unchanged month runs once and updates clock using completion time`() = runTest {
        var now = beforeRollover
        val completedAt = beforeRollover.plusSeconds(20)
        val requested = mutableListOf<Pair<String, LocalDateTime>>()
        val clocks = mutableListOf<LocalDateTime>()
        val result = refreshSelectedAttendanceMonth(
            selectedMonth = { "2026-09" },
            now = { now },
            refreshMonth = { month, startedAt ->
                requested += month to startedAt
                now = completedAt
                AttendanceRefreshResult.Success(month, 30)
            },
            updateClock = { clocks += it }
        )
        assertThat(result).isEqualTo(AttendanceRefreshResult.Success("2026-09", 30))
        assertThat(requested).containsExactly("2026-09" to beforeRollover)
        assertThat(clocks).containsExactly(completedAt)
    }

    @Test fun `historical selection stays selected when attendance clock rolls over`() = runTest {
        var selected = "2026-08"
        var clock = beforeRollover
        var now = beforeRollover
        val requests = mutableListOf<String>()
        val result = refreshSelectedAttendanceMonth(
            selectedMonth = { selected },
            now = { now },
            refreshMonth = { month, _ ->
                requests += month
                now = afterRollover
                AttendanceRefreshResult.Success(month, 31)
            },
            updateClock = { next ->
                val previousMonth = YearMonth.from(attendanceDate(clock)).toString()
                val nextMonth = YearMonth.from(attendanceDate(next)).toString()
                if (previousMonth != nextMonth && selected == previousMonth) selected = nextMonth
                clock = next
            }
        )
        assertThat(selected).isEqualTo("2026-08")
        assertThat(clock).isEqualTo(afterRollover)
        assertThat(requests).containsExactly("2026-08", "2026-08").inOrder()
        assertThat(result).isEqualTo(AttendanceRefreshResult.Success("2026-08", 31))
    }

    @Test fun `historical month request crossing five waits for refreshed current day context`() = runTest {
        var now = beforeRollover
        val previousResponse = CompletableDeferred<AttendanceRefreshResult>()
        val currentResponse = CompletableDeferred<AttendanceRefreshResult>()
        val requested = mutableListOf<Pair<String, LocalDateTime>>()
        val refresh = async {
            refreshSelectedAttendanceMonth(
                selectedMonth = { "2026-08" },
                now = { now },
                refreshMonth = { month, startedAt ->
                    requested += month to startedAt
                    if (requested.size == 1) previousResponse.await() else currentResponse.await()
                },
                updateClock = {}
            )
        }
        runCurrent()
        now = afterRollover
        previousResponse.complete(AttendanceRefreshResult.Success("2026-08", 31))
        runCurrent()
        assertThat(requested).containsExactly(
            "2026-08" to beforeRollover, "2026-08" to afterRollover
        ).inOrder()
        assertThat(refresh.isCompleted).isFalse()

        val failure = AttendanceRefreshResult.Unreachable("current day refresh failed")
        currentResponse.complete(failure)
        assertThat(refresh.await()).isEqualTo(failure)
    }

    @Test fun `session expiry returns immediately even after month selection changes`() = runTest {
        var selected = "2026-09"
        val response = CompletableDeferred<AttendanceRefreshResult>()
        val requested = mutableListOf<String>()
        val refresh = async {
            refreshSelectedAttendanceMonth(
                selectedMonth = { selected },
                now = { afterRollover },
                refreshMonth = { month, _ -> requested += month; response.await() },
                updateClock = {}
            )
        }
        runCurrent()
        selected = "2026-10"
        response.complete(AttendanceRefreshResult.SessionExpired)
        assertThat(refresh.await()).isEqualTo(AttendanceRefreshResult.SessionExpired)
        assertThat(requested).containsExactly("2026-09")
    }

    @Test fun `cancelling an in flight refresh stops retries and propagates to request`() = runTest {
        var selected = "2026-09"
        val requested = mutableListOf<String>()
        var requestCancelled = false
        var returned = false
        var clocksUpdated = 0
        val refresh = launch {
            refreshSelectedAttendanceMonth(
                selectedMonth = { selected },
                now = { afterRollover },
                refreshMonth = { month, _ ->
                    requested += month
                    try { awaitCancellation() } finally { requestCancelled = true }
                },
                updateClock = { clocksUpdated++ }
            )
            returned = true
        }
        runCurrent()
        selected = "2026-10"
        refresh.cancelAndJoin()
        assertThat(requestCancelled).isTrue()
        assertThat(returned).isFalse()
        assertThat(clocksUpdated).isEqualTo(0)
        assertThat(requested).containsExactly("2026-09")
    }
}
