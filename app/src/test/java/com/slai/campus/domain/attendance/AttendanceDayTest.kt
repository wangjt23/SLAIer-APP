package com.slai.campus.domain.attendance

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.feature.attendance.AttendanceUiState
import com.slai.campus.feature.home.HomeUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.YearMonth

/** Synthetic overnight stays exercise the same date, cached rows, and pairing used by both cards. */
@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceDayTest {
    private class Repository : AttendanceRepository {
        val daySubscriptions = mutableListOf<LocalDate>()
        val rangeSubscriptions = mutableListOf<Pair<LocalDate, LocalDate>>()
        val refreshes = mutableListOf<String>()
        val punchRefreshes = mutableListOf<Pair<LocalDate, LocalDate>>()
        val punches = MutableStateFlow(emptyList<AttendancePunch>())
        val delayedDays = mutableMapOf<LocalDate, CompletableDeferred<Unit>>()
        var summaryResult: AttendanceRefreshResult? = null
        var punchResult: AttendanceRefreshResult? = null
        val summaryResults = mutableMapOf<String, AttendanceRefreshResult>()
        val periodResults = mutableMapOf<String, AttendancePeriodResult>()
        val resolutionDates = mutableListOf<LocalDate>()
        var currentPeriodResult: AttendancePeriodResult? = null
        override fun observePeriods() = flowOf(emptyList<AttendancePeriod>())
        override suspend fun resolvePeriod(date: LocalDate): AttendancePeriodResult {
            resolutionDates += date
            return currentPeriodResult ?: periodForMonth(YearMonth.from(date).toString())
        }
        override suspend fun periodForMonth(month: String): AttendancePeriodResult {
            val calendar = YearMonth.parse(month)
            return periodResults[month] ?: AttendancePeriodResult.Found(
                AttendancePeriod(month, calendar.atDay(1), calendar.atEndOfMonth()))
        }

        override fun observeDay(date: LocalDate): Flow<AttendanceRecord?> = flow {
            daySubscriptions += date
            delayedDays[date]?.await()
            emit(AttendanceRecord(date, durationText = "0"))
        }

        override fun observePunches(from: LocalDate, to: LocalDate): Flow<List<AttendancePunch>> = flow {
            rangeSubscriptions += from to to
            emitAll(punches.map { rows -> rows.filter { it.date in from..to } })
        }

        override fun observeMonth(month: String) = flowOf(AttendanceMonth(month))

        override suspend fun refresh(month: String): AttendanceRefreshResult {
            refreshes += month
            return summaryResults[month] ?: summaryResult ?: AttendanceRefreshResult.Success(month, 1)
        }

        override suspend fun refreshPunches(from: LocalDate, to: LocalDate): AttendanceRefreshResult {
            punchRefreshes += from to to
            return punchResult ?: AttendanceRefreshResult.Success(to.toString(), punches.value.size)
        }
    }

    private fun time(value: String) = LocalDateTime.parse(value)
    private fun date(value: String) = LocalDate.parse(value)
    private fun punch(value: String, direction: PunchDirection) = AttendancePunch(
        id = "$value-$direction", time = time(value), direction = direction, place = "教学楼"
    )

    @Test fun `midnight keeps the same attendance subscription until five`() = runTest {
        val repository = Repository()
        val clock = MutableStateFlow(time("2026-10-22T23:59:59"))
        val snapshots = mutableListOf<CurrentAttendance>()
        backgroundScope.launch { repository.observeCurrentAttendance(clock).collect { snapshots += it } }
        runCurrent()

        clock.value = time("2026-10-23T00:00:00")
        runCurrent()
        clock.value = time("2026-10-23T04:59:59")
        runCurrent()
        assertThat(repository.daySubscriptions).containsExactly(date("2026-10-22"))
        assertThat(repository.rangeSubscriptions).containsExactly(date("2026-10-21") to date("2026-10-23"))
        assertThat(snapshots.map { it.date }).containsExactly(date("2026-10-22"))

        clock.value = time("2026-10-23T05:00:00")
        runCurrent()
        assertThat(repository.daySubscriptions).containsExactly(date("2026-10-22"), date("2026-10-23")).inOrder()
        assertThat(repository.rangeSubscriptions).containsExactly(
            date("2026-10-21") to date("2026-10-23"),
            date("2026-10-22") to date("2026-10-24")
        ).inOrder()
        assertThat(snapshots.last().date).isEqualTo(date("2026-10-23"))
        assertThat(snapshots.all { it.record?.date == it.date }).isTrue()
        assertThat(repository.refreshes).isEmpty()
        assertThat(repository.punchRefreshes).isEmpty()
    }

    @Test fun `a new collection after five reads the current day without a prior rollover event`() = runTest {
        val repository = Repository()
        val clock = MutableStateFlow(time("2026-10-23T04:59:59"))
        assertThat(repository.observeCurrentAttendance(clock).first().date).isEqualTo(date("2026-10-22"))
        clock.value = time("2026-10-23T05:00:00")
        assertThat(repository.observeCurrentAttendance(clock).first().date).isEqualTo(date("2026-10-23"))
        assertThat(repository.daySubscriptions).containsExactly(date("2026-10-22"), date("2026-10-23")).inOrder()
    }

    @Test fun `a running real clock subscription switches the attendance cache at five`() = runTest {
        var instant = Instant.parse("2026-10-22T20:59:59Z")
        val zone = ZoneId.of("Asia/Shanghai")
        val clock = object : Clock() {
            override fun instant() = instant
            override fun getZone() = zone
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant, zone)
        }
        val repository = Repository()
        val snapshots = mutableListOf<CurrentAttendance>()
        backgroundScope.launch {
            repository.observeCurrentAttendance(TimeProvider(clock).observeDateTime()).collect { snapshots += it }
        }
        runCurrent()
        assertThat(snapshots.single().date).isEqualTo(date("2026-10-22"))

        instant = instant.plusSeconds(1)
        advanceTimeBy(30_000)
        runCurrent()
        assertThat(snapshots.last().date).isEqualTo(date("2026-10-23"))
        assertThat(repository.daySubscriptions).containsExactly(date("2026-10-22"), date("2026-10-23")).inOrder()
    }

    @Test fun `rollover never labels old cached rows as the new attendance day`() = runTest {
        val repository = Repository()
        val nextDay = date("2026-10-23")
        val nextDayReady = CompletableDeferred<Unit>()
        repository.delayedDays[nextDay] = nextDayReady
        val clock = MutableStateFlow(time("2026-10-23T04:59:59"))
        val snapshots = mutableListOf<CurrentAttendance>()
        backgroundScope.launch { repository.observeCurrentAttendance(clock).collect { snapshots += it } }
        runCurrent()

        clock.value = time("2026-10-23T05:00:00")
        runCurrent()
        repository.punches.value = listOf(punch("2026-10-23T05:01:00", PunchDirection.IN))
        runCurrent()
        assertThat(snapshots.map { it.date }).containsExactly(date("2026-10-22"))

        nextDayReady.complete(Unit)
        runCurrent()
        val latest = snapshots.last()
        assertThat(latest.date).isEqualTo(nextDay)
        assertThat(latest.record?.date).isEqualTo(nextDay)
        assertThat(latest.punches.single().date).isEqualTo(nextDay)
        assertThat(snapshots.all { it.record?.date == it.date }).isTrue()
    }

    @Test fun `cached overnight observation includes the entry and next calendar day's exit`() = runTest {
        val repository = Repository()
        repository.punches.value = listOf(
            punch("2026-10-21T12:00:00", PunchDirection.OUT),
            punch("2026-10-22T23:00:00", PunchDirection.IN),
            punch("2026-10-23T00:30:00", PunchDirection.OUT),
            punch("2026-10-24T09:00:00", PunchDirection.IN)
        )
        val snapshot = repository.observeCurrentAttendance(flowOf(time("2026-10-23T01:00:00"))).first()
        assertThat(snapshot.date).isEqualTo(date("2026-10-22"))
        assertThat(snapshot.punches).hasSize(3)
        assertThat(PunchPairing.of(snapshot.date, snapshot.punches, time("2026-10-23T01:00:00")).minutesAt(time("2026-10-23T01:00:00")))
            .isEqualTo(90)
    }

    @Test fun `current refresh queries the resolved period and its complete overnight context`() = runTest {
        listOf(
            Triple("2026-11-01T00:30:00", "2026-10", date("2026-09-30") to date("2026-11-01")),
            Triple("2027-01-01T04:59:59", "2026-12", date("2026-11-30") to date("2027-01-01")),
            Triple("2027-01-01T05:00:00", "2027-01", date("2026-12-31") to date("2027-02-01"))
        ).forEach { (now, expectedMonth, expectedRange) ->
            val repository = Repository()
            val result = repository.refreshCurrentAttendance(time(now))
            assertThat(repository.refreshes).containsExactly(expectedMonth)
            assertThat(repository.punchRefreshes).containsExactly(expectedRange)
            assertThat(result).isEqualTo(AttendanceRefreshResult.Success(expectedMonth, 1))
        }
    }

    @Test fun `current attendance refresh keeps punch failure visible and avoids it after expiry`() = runTest {
        val repository = Repository()
        repository.punchResult = AttendanceRefreshResult.Unreachable("campus unavailable")
        assertThat(repository.refreshCurrentAttendance(time("2026-10-23T00:30:00")))
            .isEqualTo(repository.punchResult)

        repository.summaryResult = AttendanceRefreshResult.SessionExpired
        repository.punchRefreshes.clear()
        assertThat(repository.refreshCurrentAttendance(time("2026-10-23T00:30:00")))
            .isEqualTo(AttendanceRefreshResult.SessionExpired)
        assertThat(repository.punchRefreshes).isEmpty()
    }

    @Test fun `late October current refresh uses November school statistics and all of its punches`() = runTest {
        val period = AttendancePeriod("2026-11", date("2026-10-26"), date("2026-11-29"))
        val repository = Repository().apply { currentPeriodResult = AttendancePeriodResult.Found(period) }
        val result = repository.refreshCurrentAttendance(time("2026-10-31T04:23:00"))
        assertThat(repository.resolutionDates).containsExactly(date("2026-10-30"))
        assertThat(repository.refreshes).containsExactly("2026-11")
        assertThat(repository.punchRefreshes).containsExactly(date("2026-10-25") to date("2026-11-30"))
        assertThat(result).isEqualTo(AttendanceRefreshResult.Success("2026-11", 1))
    }

    @Test fun `a calendar opening can still refresh the previous school month`() = runTest {
        val period = AttendancePeriod("2026-05", date("2026-05-04"), date("2026-06-07"))
        val repository = Repository().apply { currentPeriodResult = AttendancePeriodResult.Found(period) }
        repository.refreshCurrentAttendance(time("2026-06-01T12:00:00"))
        assertThat(repository.refreshes).containsExactly("2026-05")
        assertThat(repository.punchRefreshes).containsExactly(date("2026-05-03") to date("2026-06-08"))
    }

    @Test fun `a resolved school period is reused when refreshing its selected month`() = runTest {
        val period = AttendancePeriod("2026-11", date("2026-10-26"), date("2026-11-29"))
        val repository = Repository().apply {
            currentPeriodResult = AttendancePeriodResult.Found(period)
            periodResults["2026-11"] = AttendancePeriodResult.Found(period)
        }
        repository.refreshMonthWithCurrentDay("2026-11", time("2026-10-31T04:23:00"))
        assertThat(repository.refreshes).containsExactly("2026-11")
        assertThat(repository.punchRefreshes).containsExactly(date("2026-10-25") to date("2026-11-30"))
    }

    @Test fun `unavailable period never submits a guessed calendar month or reports sync success`() = runTest {
        listOf(AttendanceRefreshResult.Unreachable("timeout"), AttendanceRefreshResult.Offline(true),
            AttendanceRefreshResult.SchemaChanged("ambiguous ranges"), AttendanceRefreshResult.SessionExpired,
            AttendanceRefreshResult.Superseded).forEach { failure ->
            val repository = Repository().apply { currentPeriodResult = AttendancePeriodResult.Unavailable(failure) }
            assertThat(repository.refreshCurrentAttendance(time("2026-10-31T04:23:00"))).isEqualTo(failure)
            assertThat(repository.refreshes).isEmpty()
            assertThat(repository.punchRefreshes).isEmpty()
        }
    }

    @Test fun `selected period lookup failure is visible without damaging its cache`() = runTest {
        val failure = AttendanceRefreshResult.SchemaChanged("invalid school weeks")
        val repository = Repository().apply { periodResults["2026-11"] = AttendancePeriodResult.Unavailable(failure) }
        assertThat(repository.refreshMonthWithCurrentDay("2026-11", time("2026-10-31T04:23:00"))).isEqualTo(failure)
        assertThat(repository.refreshes).isEmpty()
        assertThat(repository.punchRefreshes).isEmpty()
    }

    @Test fun `historical success cannot conceal a failure resolving the current school month`() = runTest {
        val failure = AttendanceRefreshResult.Unreachable("weeks unavailable")
        val repository = Repository().apply { currentPeriodResult = AttendancePeriodResult.Unavailable(failure) }
        assertThat(repository.refreshMonthWithCurrentDay("2026-09", time("2026-10-31T04:23:00"))).isEqualTo(failure)
        assertThat(repository.refreshes).containsExactly("2026-09")
    }

    @Test fun `an inconsistent resolved period cannot query unrelated current statistics`() = runTest {
        val repository = Repository().apply {
            currentPeriodResult = AttendancePeriodResult.Found(
                AttendancePeriod("2026-10", date("2026-09-28"), date("2026-10-25")))
        }
        assertThat(repository.refreshCurrentAttendance(time("2026-10-31T04:23:00")))
            .isInstanceOf(AttendanceRefreshResult.SchemaChanged::class.java)
        assertThat(repository.refreshes).isEmpty()
    }

    @Test fun `selected month lookup cannot silently return another months period`() = runTest {
        val repository = Repository().apply {
            periodResults["2026-10"] = AttendancePeriodResult.Found(
                AttendancePeriod("2026-11", date("2026-10-26"), date("2026-11-29")))
        }
        assertThat(repository.refreshMonthWithCurrentDay("2026-10", time("2026-10-31T04:23:00")))
            .isInstanceOf(AttendanceRefreshResult.SchemaChanged::class.java)
        assertThat(repository.refreshes).isEmpty()
        assertThat(repository.punchRefreshes).isEmpty()
    }

    @Test fun `selected refresh does not accept an inconsistent current period even if labels match`() = runTest {
        val period = AttendancePeriod("2026-10", date("2026-09-28"), date("2026-10-25"))
        val repository = Repository().apply {
            currentPeriodResult = AttendancePeriodResult.Found(period)
            periodResults["2026-10"] = AttendancePeriodResult.Found(period)
        }
        assertThat(repository.refreshMonthWithCurrentDay("2026-10", time("2026-10-31T04:23:00")))
            .isInstanceOf(AttendanceRefreshResult.SchemaChanged::class.java)
        assertThat(repository.refreshes).containsExactly("2026-10")
    }

    @Test fun `browsing the new month before five refreshes the preceding month's summary despite overlapping punches`() = runTest {
        val repository = Repository()
        val result = repository.refreshMonthWithCurrentDay("2026-11", time("2026-11-01T00:30:00"))
        assertThat(repository.refreshes).containsExactly("2026-11", "2026-10").inOrder()
        assertThat(repository.punchRefreshes).containsExactly(
            date("2026-10-31") to date("2026-12-01"),
            date("2026-09-30") to date("2026-11-01")
        ).inOrder()
        assertThat(result).isEqualTo(AttendanceRefreshResult.Success("2026-11", 1))
    }

    @Test fun `refreshing the selected current attendance month does not repeat summary or punch requests`() = runTest {
        val repository = Repository()
        repository.refreshMonthWithCurrentDay("2026-10", time("2026-11-01T04:59:59"))
        assertThat(repository.refreshes).containsExactly("2026-10")
        assertThat(repository.punchRefreshes).containsExactly(date("2026-09-30") to date("2026-11-01"))
    }

    @Test fun `selected month expiry stops refresh while current-day failures remain visible`() = runTest {
        val now = time("2026-11-01T00:30:00")
        val expiredSelection = Repository().apply {
            summaryResults["2026-11"] = AttendanceRefreshResult.SessionExpired
        }
        assertThat(expiredSelection.refreshMonthWithCurrentDay("2026-11", now))
            .isEqualTo(AttendanceRefreshResult.SessionExpired)
        assertThat(expiredSelection.refreshes).containsExactly("2026-11")
        assertThat(expiredSelection.punchRefreshes).isEmpty()

        val expiredCurrent = Repository().apply {
            summaryResults["2026-10"] = AttendanceRefreshResult.SessionExpired
        }
        assertThat(expiredCurrent.refreshMonthWithCurrentDay("2026-11", now))
            .isEqualTo(AttendanceRefreshResult.SessionExpired)
        assertThat(expiredCurrent.refreshes).containsExactly("2026-11", "2026-10").inOrder()
        assertThat(expiredCurrent.punchRefreshes).containsExactly(date("2026-10-31") to date("2026-12-01"))

        val failedCurrent = Repository().apply {
            summaryResults["2026-10"] = AttendanceRefreshResult.ServerError(503)
        }
        assertThat(failedCurrent.refreshMonthWithCurrentDay("2026-11", now))
            .isEqualTo(AttendanceRefreshResult.ServerError(503))
        assertThat(failedCurrent.refreshes).containsExactly("2026-11", "2026-10").inOrder()
        assertThat(failedCurrent.punchRefreshes).hasSize(2)
    }

    @Test fun `both cards keep accumulating after midnight instead of showing the new day's zero`() {
        val now = time("2026-10-23T00:30:00")
        val selected = attendanceDate(now)
        val record = AttendanceRecord(selected, durationText = "0")
        val daily = PunchPairing.of(selected, listOf(punch("2026-10-22T23:00:00", PunchDirection.IN)), now)
        val attendance = AttendanceUiState(today = record, todayDate = selected, todayDaily = daily, nowDateTime = now)
        val home = HomeUiState(attendance = record, attendanceDaily = daily, nowDateTime = now)
        assertThat(attendance.todayMinutes).isEqualTo(90)
        assertThat(home.attendanceMinutes).isEqualTo(90)
        assertThat(attendance.currentlyInside).isTrue()
        assertThat(home.attendanceInside).isTrue()

        val later = time("2026-10-23T01:00:00")
        assertThat(attendance.copy(nowDateTime = later).todayMinutes).isEqualTo(120)
        assertThat(home.copy(nowDateTime = later).attendanceMinutes).isEqualTo(120)
    }

    @Test fun `a recorded after-midnight exit stops both card timers without resetting the total`() {
        val now = time("2026-10-23T02:00:00")
        val selected = attendanceDate(now)
        val record = AttendanceRecord(selected, durationText = "0")
        val daily = PunchPairing.of(selected, listOf(
            punch("2026-10-22T23:00:00", PunchDirection.IN),
            punch("2026-10-23T00:30:00", PunchDirection.OUT)
        ), now)
        val attendance = AttendanceUiState(today = record, todayDate = selected, todayDaily = daily, nowDateTime = now)
        val home = HomeUiState(attendance = record, attendanceDaily = daily, nowDateTime = now)
        assertThat(attendance.todayMinutes).isEqualTo(90)
        assertThat(home.attendanceMinutes).isEqualTo(90)
        assertThat(attendance.currentlyInside).isFalse()
        assertThat(home.attendanceInside).isFalse()
        assertThat(attendance.copy(nowDateTime = time("2026-10-23T04:59:59")).todayMinutes).isEqualTo(90)
        assertThat(home.copy(nowDateTime = time("2026-10-23T04:59:59")).attendanceMinutes).isEqualTo(90)
    }

    @Test fun `unsettled school zero cannot replace the continuing day's local detail before five`() {
        val now = time("2026-10-23T00:30:00")
        val selected = date("2026-10-22")
        val record = AttendanceRecord(selected, durationText = "0")
        val daily = PunchPairing.of(selected, listOf(punch("2026-10-22T23:00:00", PunchDirection.IN)), now)
        assertThat(record.displayMinutes(daily, now)).isEqualTo(90)
        assertThat(record.displayMinutes(daily, time("2026-10-23T04:59:59"))).isEqualTo(359)
    }

    @Test fun `school historical zero and nonzero totals regain priority at five`() {
        val now = time("2026-10-23T05:00:00")
        val selected = date("2026-10-22")
        val daily = PunchPairing.of(selected, listOf(
            punch("2026-10-22T23:00:00", PunchDirection.IN),
            punch("2026-10-23T00:30:00", PunchDirection.OUT)
        ), now)
        assertThat(daily.minutesAt(now)).isEqualTo(90)
        assertThat(AttendanceRecord(selected, durationText = "0").displayMinutes(daily, now)).isEqualTo(0)
        assertThat(AttendanceRecord(selected, durationText = "02:00:00").displayMinutes(daily, now)).isEqualTo(120)
    }
}
