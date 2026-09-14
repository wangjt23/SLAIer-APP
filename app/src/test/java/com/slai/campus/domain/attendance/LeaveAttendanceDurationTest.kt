package com.slai.campus.domain.attendance

import com.google.common.truth.Truth.assertThat
import com.slai.campus.feature.attendance.AttendanceUiState
import com.slai.campus.feature.home.HomeUiState
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Synthetic leave credits must never be presented as physical time on site. */
class LeaveAttendanceDurationTest {
    private val date = LocalDate.of(2026, 1, 10)
    private val later = LocalDateTime.parse("2026-01-13T12:00:00")
    private val leave = AttendanceRecord(date, leave = true, qualified = true, durationText = "14:59:11", durationMinutes = 899)
    private fun punch(time: LocalDateTime, direction: PunchDirection) = AttendancePunch(
        id = "$time-$direction", time = time, direction = direction, place = "教学楼"
    )
    private fun paired() = PunchPairing.of(date, listOf(
        punch(date.atTime(9, 8), PunchDirection.IN),
        punch(date.atTime(18, 7, 11), PunchDirection.OUT)
    ), later)

    @Test fun `leave day uses real gate time without including school leave credits`() {
        assertThat(leave.displayMinutes(paired(), later)).isEqualTo(539)
        assertThat(leave.qualified).isTrue()
        assertThat(leave.reportedMinutes).isEqualTo(899)
    }

    @Test fun `leave day with no gate records stays unknown rather than using credited duration`() {
        assertThat(leave.displayMinutes(null, later)).isNull()
        assertThat(leave.displayMinutes(DailyAttendance(date), later)).isNull()
    }

    @Test fun `today's leave without gate data does not use credited time on either screen`() {
        val now = date.atTime(20, 0)
        val home = HomeUiState(attendance = leave, nowDateTime = now)
        val attendance = AttendanceUiState(today = leave, todayDate = date, nowDateTime = now)
        assertThat(home.attendanceMinutes).isNull()
        assertThat(attendance.todayMinutes).isNull()
        assertThat(attendance.goalReached).isFalse()
    }

    @Test fun `leave day still pairs an overnight stay into the entry date`() {
        val daily = PunchPairing.of(date, listOf(
            punch(date.atTime(23, 0), PunchDirection.IN),
            punch(date.plusDays(1).atTime(4, 30), PunchDirection.OUT)
        ), later)
        assertThat(leave.displayMinutes(daily, later)).isEqualTo(330)
    }

    @Test fun `incomplete leave records retain closed intervals but never fall back to credited time`() {
        val daily = PunchPairing.of(date, listOf(
            punch(date.atTime(9, 0), PunchDirection.IN),
            punch(date.atTime(11, 0), PunchDirection.OUT),
            punch(date.atTime(16, 0), PunchDirection.IN)
        ), later)
        assertThat(leave.displayMinutes(daily, later)).isEqualTo(120)
    }

    @Test fun `orphan exits and expired entries alone do not establish a physical duration`() {
        val orphan = PunchPairing.of(date, listOf(punch(date.atTime(10, 0), PunchDirection.OUT)), later)
        val expired = PunchPairing.of(date, listOf(punch(date.atTime(16, 0), PunchDirection.IN)), later)
        assertThat(leave.displayMinutes(orphan, later)).isNull()
        assertThat(leave.displayMinutes(expired, later)).isNull()
    }

    @Test fun `a valid ongoing leave-day session continues accumulating live time`() {
        val now = date.atTime(12, 0)
        val open = PunchPairing.of(date, listOf(punch(date.atTime(9, 0), PunchDirection.IN)), now)
        assertThat(leave.displayMinutes(open, now)).isEqualTo(180)
        assertThat(HomeUiState(attendance = leave, attendanceDaily = open, nowDateTime = now).attendanceMinutes).isEqualTo(180)
        assertThat(AttendanceUiState(today = leave, todayDaily = open, nowDateTime = now).todayMinutes).isEqualTo(180)
    }

    @Test fun `non-leave historical days retain the school-total priority`() {
        listOf(false, null).forEach { flag ->
            assertThat(leave.copy(leave = flag).displayMinutes(paired(), later)).isEqualTo(899)
        }
    }

    @Test fun `a valid sub-minute visit remains zero minutes instead of unknown`() {
        val daily = PunchPairing.of(date, listOf(
            punch(date.atTime(9, 0), PunchDirection.IN),
            punch(date.atTime(9, 0, 30), PunchDirection.OUT)
        ), later)
        assertThat(leave.displayMinutes(daily, later)).isEqualTo(0)
    }
}
