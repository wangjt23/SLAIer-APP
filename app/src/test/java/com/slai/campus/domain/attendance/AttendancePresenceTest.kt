package com.slai.campus.domain.attendance

import com.google.common.truth.Truth.assertThat
import com.slai.campus.feature.attendance.AttendanceUiState
import com.slai.campus.feature.home.HomeUiState
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Presence follows the full timeline; daily totals retain the entry's natural calendar date. */
class AttendancePresenceTest {
    private val previous = LocalDate.parse("2026-10-22")
    private val now = LocalDateTime.parse("2026-10-23T02:00:00")
    private val reentry = listOf(
        punch("2026-10-22T23:00:00", PunchDirection.IN),
        punch("2026-10-23T00:30:00", PunchDirection.OUT),
        punch("2026-10-23T01:00:00", PunchDirection.IN)
    )

    @Test fun `return after midnight shows inside on both cards without moving daily totals`() {
        val daily = PunchPairing.of(previous, reentry, now)
        val record = AttendanceRecord(previous, durationText = "0")
        val attendance = AttendanceUiState(
            today = record, todayDate = previous, todayDaily = daily, nowDateTime = now,
            currentPunches = reentry
        )
        val home = HomeUiState(
            attendance = record, attendanceDaily = daily, nowDateTime = now, currentPunches = reentry
        )

        assertThat(daily.currentlyInsideAt(now)).isFalse()
        assertThat(attendance.todayMinutes).isEqualTo(90)
        assertThat(home.attendanceMinutes).isEqualTo(90)
        assertThat(attendance.currentlyInside).isTrue()
        assertThat(home.attendanceInside).isTrue()
        assertThat(PunchPairing.of(previous.plusDays(1), reentry, now).minutesAt(now)).isEqualTo(60)
    }

    @Test fun `exit from the new date closes both presence indicators`() {
        val later = LocalDateTime.parse("2026-10-23T02:30:00")
        val punches = reentry + punch(later.toString(), PunchDirection.OUT)
        val daily = PunchPairing.of(previous, punches, later)
        val attendance = AttendanceUiState(
            todayDate = previous, todayDaily = daily, nowDateTime = later, currentPunches = punches
        )
        val home = HomeUiState(attendanceDaily = daily, nowDateTime = later, currentPunches = punches)

        assertThat(attendance.todayMinutes).isEqualTo(90)
        assertThat(home.attendanceMinutes).isEqualTo(90)
        assertThat(attendance.currentlyInside).isFalse()
        assertThat(home.attendanceInside).isFalse()
        assertThat(PunchPairing.of(previous.plusDays(1), punches, later).minutesAt(later)).isEqualTo(90)
    }

    @Test fun `new-date entry establishes presence even when the displayed date has no punches`() {
        val punches = reentry.takeLast(1)
        val emptyDay = PunchPairing.of(previous, punches, now)
        assertThat(AttendanceUiState(
            todayDaily = emptyDay, nowDateTime = now, currentPunches = punches
        ).currentlyInside).isTrue()
        assertThat(HomeUiState(
            attendanceDaily = emptyDay, nowDateTime = now, currentPunches = punches
        ).attendanceInside).isTrue()
        assertThat(emptyDay.minutesAt(now)).isEqualTo(0)
    }

    @Test fun `dormitory punches do not indicate teaching-building presence or revive summary state`() {
        val punches = listOf(punch("2026-10-23T01:00:00", PunchDirection.IN, "宿舍楼"))
        val staleSummary = AttendanceRecord(previous, swipes = listOf(LocalTime.of(23, 0)))
        assertThat(PunchPairing.currentlyInsideAt(punches, now)).isFalse()
        assertThat(AttendanceUiState(
            today = staleSummary, nowDateTime = now, currentPunches = punches
        ).currentlyInside).isFalse()
        assertThat(HomeUiState(
            attendance = staleSummary, nowDateTime = now, currentPunches = punches
        ).attendanceInside).isFalse()
    }

    @Test fun `expired raw entry wins over stale summary and daily presence`() {
        val punches = listOf(punch("2026-10-21T23:00:00", PunchDirection.IN))
        val staleSummary = AttendanceRecord(previous, swipes = listOf(LocalTime.of(23, 0)))
        val staleDaily = PunchPairing.of(previous, reentry.take(1), now)
        assertThat(PunchPairing.currentlyInsideAt(punches, now)).isFalse()
        assertThat(AttendanceUiState(
            today = staleSummary, todayDaily = staleDaily, nowDateTime = now, currentPunches = punches
        ).currentlyInside).isFalse()
        assertThat(HomeUiState(
            attendance = staleSummary, attendanceDaily = staleDaily, nowDateTime = now, currentPunches = punches
        ).attendanceInside).isFalse()
    }

    @Test fun `future exit cannot close the current stay`() {
        val punches = reentry + punch("2026-10-23T02:30:00", PunchDirection.OUT)
        assertThat(PunchPairing.currentlyInsideAt(punches, now)).isTrue()
        assertThat(AttendanceUiState(nowDateTime = now, currentPunches = punches).currentlyInside).isTrue()
        assertThat(HomeUiState(nowDateTime = now, currentPunches = punches).attendanceInside).isTrue()
        assertThat(PunchPairing.currentlyInsideAt(punches, LocalDateTime.parse("2026-10-23T02:30:00"))).isFalse()
    }

    @Test fun `future entry cannot establish current presence`() {
        val punches = listOf(punch("2026-10-23T02:30:00", PunchDirection.IN))
        assertThat(PunchPairing.currentlyInsideAt(punches, now)).isFalse()
        assertThat(AttendanceUiState(nowDateTime = now, currentPunches = punches).currentlyInside).isFalse()
        assertThat(HomeUiState(nowDateTime = now, currentPunches = punches).attendanceInside).isFalse()
        assertThat(PunchPairing.currentlyInsideAt(punches, LocalDateTime.parse("2026-10-23T02:30:00"))).isTrue()
    }

    @Test fun `daily-only cards retain valid presence and reject stale summary after exit`() {
        val staleSummary = AttendanceRecord(previous, swipes = listOf(LocalTime.of(23, 0)))
        val opened = PunchPairing.of(previous, reentry.take(1), now)
        val closed = PunchPairing.of(previous, reentry.take(2), now)
        assertThat(AttendanceUiState(todayDaily = opened, nowDateTime = now).currentlyInside).isTrue()
        assertThat(HomeUiState(attendanceDaily = opened, nowDateTime = now).attendanceInside).isTrue()
        assertThat(AttendanceUiState(
            today = staleSummary, todayDaily = closed, nowDateTime = now
        ).currentlyInside).isFalse()
        assertThat(HomeUiState(
            attendance = staleSummary, attendanceDaily = closed, nowDateTime = now
        ).attendanceInside).isFalse()
    }

    @Test fun `unknown direction and empty input do not create presence`() {
        assertThat(PunchPairing.currentlyInsideAt(emptyList(), now)).isFalse()
        assertThat(PunchPairing.currentlyInsideAt(
            listOf(punch("2026-10-23T01:00:00", PunchDirection.UNKNOWN)), now
        )).isFalse()
    }

    private fun punch(time: String, direction: PunchDirection, place: String = "教学楼") = AttendancePunch(
        id = "$time-$direction-$place", time = LocalDateTime.parse(time), direction = direction, place = place
    )
}
