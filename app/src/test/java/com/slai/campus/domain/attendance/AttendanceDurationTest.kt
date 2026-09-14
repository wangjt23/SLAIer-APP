package com.slai.campus.domain.attendance

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** Synthetic totals ensure incomplete native pairing never replaces the school's historical total. */
class AttendanceDurationTest {
    private val day = LocalDate.of(2026, 1, 10)
    private val now = LocalDateTime.parse("2026-01-13T12:00:00")
    private val incomplete = PunchPairing.of(day, listOf(
        AttendancePunch("in", day.atTime(9, 0), PunchDirection.IN),
        AttendancePunch("out", day.atTime(11, 0), PunchDirection.OUT),
        AttendancePunch("late-in", day.atTime(16, 0), PunchDirection.IN)
    ), now)

    @Test fun `historical school total wins over a shorter locally paired total`() {
        val record = AttendanceRecord(day, durationText = "12:53:47", durationMinutes = 773)
        assertThat(incomplete.minutesAt(now)).isEqualTo(120)
        assertThat(record.displayMinutes(incomplete, now)).isEqualTo(773)
    }

    @Test fun `historical zero is authoritative too`() {
        val record = AttendanceRecord(day, durationText = "0")
        assertThat(record.displayMinutes(incomplete, now)).isEqualTo(0)
    }

    @Test fun `live unsettled school zero does not erase time calculated from today's punches`() {
        val sameDay = day.atTime(18, 0)
        val record = AttendanceRecord(day, durationText = "00:00:00", durationMinutes = 0)
        assertThat(record.displayMinutes(incomplete, sameDay)).isEqualTo(240)
    }

    @Test fun `missing historical total can fall back to paired records`() {
        assertThat(AttendanceRecord(day).displayMinutes(incomplete, now)).isEqualTo(120)
    }

    @Test fun `the displayed school duration takes precedence over conflicting numeric fields`() {
        assertThat(AttendanceRecord(day, durationText = "12:53:47", durationMinutes = 120).reportedMinutes).isEqualTo(773)
    }

    @Test fun `malformed duration text falls back to a valid numeric total`() {
        assertThat(AttendanceRecord(day, durationText = "bad", durationMinutes = 120).reportedMinutes).isEqualTo(120)
        assertThat(AttendanceRecord(day, durationText = "12:90:00").reportedMinutes).isNull()
    }

    @Test fun `month queries include next month's first day to close the last overnight stay`() {
        val (from, to) = monthPunchRange(YearMonth.of(2026, 1))
        assertThat(from).isAtMost(LocalDate.of(2025, 12, 28))
        assertThat(to).isEqualTo(LocalDate.of(2026, 2, 1))
    }
}
