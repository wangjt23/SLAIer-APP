package com.slai.campus.domain.attendance

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Clock-only school summaries must obey the same bounded overnight rules as gate events. */
class AttendanceSummaryOvernightTest {
    private val date = LocalDate.of(2026, 10, 22)
    private val afterMidnight = LocalDateTime.parse("2026-10-23T00:30:00")
    private fun record(vararg times: String) = AttendanceRecord(
        date, swipes = times.map(LocalTime::parse)
    )

    @Test fun `closed daytime stays plus an ongoing overnight stay are both accumulated`() {
        val record = record("10:00", "12:00", "23:00")
        assertThat(record.checkedInMinutesAt(afterMidnight)).isEqualTo(210)
        assertThat(record.displayMinutes(null, afterMidnight)).isEqualTo(210)
        assertThat(record.isCurrentlyInsideAt(afterMidnight)).isTrue()
    }

    @Test fun `a single open swipe continues counting after midnight`() {
        val record = record("23:00")
        assertThat(record.checkedInMinutesAt(afterMidnight)).isEqualTo(90)
        assertThat(record.isCurrentlyInsideAt(afterMidnight)).isTrue()
    }

    @Test fun `first swipe alone has the same overnight behavior`() {
        val record = AttendanceRecord(date, firstSwipe = LocalTime.of(23, 0), durationMinutes = 0)
        assertThat(record.checkedInMinutesAt(afterMidnight)).isEqualTo(90)
        assertThat(record.isCurrentlyInsideAt(afterMidnight)).isTrue()
    }

    @Test fun `a completed overnight pair keeps its duration after the cutoff`() {
        val record = record("23:00", "00:30")
        val later = date.plusDays(2).atTime(12, 0)
        assertThat(record.checkedInMinutesAt(later)).isEqualTo(90)
        assertThat(record.displayMinutes(null, later)).isEqualTo(90)
        assertThat(record.isCurrentlyInsideAt(later)).isFalse()
    }

    @Test fun `first and last swipe may describe a completed overnight pair`() {
        val record = AttendanceRecord(
            date, firstSwipe = LocalTime.of(23, 0), lastSwipe = LocalTime.of(0, 30)
        )
        assertThat(record.checkedInMinutesAt(afterMidnight)).isEqualTo(90)
        assertThat(record.isCurrentlyInsideAt(afterMidnight)).isFalse()
    }

    @Test fun `an exit exactly at five closes the pair`() {
        assertThat(record("23:00", "05:00").checkedInMinutesAt(date.plusDays(1).atTime(6, 0)))
            .isEqualTo(360)
    }

    @Test fun `a later exit does not revive an expired pair or discard earlier valid stays`() {
        val record = record("10:00", "12:00", "23:00", "05:00:01")
        val later = date.plusDays(1).atTime(6, 0)
        assertThat(record.checkedInMinutesAt(later)).isEqualTo(120)
        assertThat(record.isCurrentlyInsideAt(later)).isFalse()
    }

    @Test fun `unclosed overnight stays expire at five and cannot grow on later dates`() {
        val record = record("23:00")
        listOf(date.plusDays(1).atTime(5, 0), date.plusDays(3).atTime(12, 0)).forEach { now ->
            assertThat(record.checkedInMinutesAt(now)).isEqualTo(0)
            assertThat(record.isCurrentlyInsideAt(now)).isFalse()
        }
        assertThat(record.checkedInMinutesAt(date.plusDays(1).atTime(4, 59))).isEqualTo(359)
    }

    @Test fun `normal same-day pairs and open stays keep their sum`() {
        val open = record("10:00", "12:00", "14:00")
        assertThat(open.checkedInMinutesAt(date.atTime(16, 0))).isEqualTo(240)
        assertThat(open.isCurrentlyInsideAt(date.atTime(16, 0))).isTrue()
        assertThat(record("10:00", "12:00").isCurrentlyInsideAt(date.atTime(16, 0))).isFalse()
    }

    @Test fun `seconds across stays are summed before rounding the total`() {
        val record = record("09:00", "10:00:40", "23:00", "00:00:40")
        assertThat(record.checkedInMinutesAt(afterMidnight)).isEqualTo(121)
    }

    @Test fun `clock-only compatibility API keeps its previous behavior`() {
        val record = record("10:00", "12:00", "23:00")
        assertThat(record.checkedInMinutes()).isEqualTo(120)
        assertThat(record.checkedInMinutes(LocalTime.of(0, 30))).isEqualTo(120)
        assertThat(record.isCurrentlyInside).isTrue()
    }

    @Test fun `settled school totals including zero retain historical priority`() {
        val later = date.plusDays(1).atTime(6, 0)
        assertThat(record("23:00", "00:30").copy(durationText = "14:59:00")
            .displayMinutes(null, later)).isEqualTo(899)
        assertThat(record("23:00", "00:30").copy(durationText = "0")
            .displayMinutes(null, later)).isEqualTo(0)
    }

    @Test fun `leave time still requires real paired gate events`() {
        val record = record("23:00", "00:30").copy(leave = true, durationMinutes = 899)
        assertThat(record.displayMinutes(null, afterMidnight)).isNull()
    }

    @Test fun `daytime reversed clocks are rejected instead of becoming a twenty-two hour stay`() {
        val record = record("12:00", "10:00")
        assertThat(record.checkedInMinutesAt(date.plusDays(1).atTime(12, 0))).isEqualTo(0)
        assertThat(record.isCurrentlyInsideAt(date.atTime(13, 0))).isFalse()
    }

    @Test fun `a second backwards clock does not invent another calendar date`() {
        val record = record("23:00", "00:30", "01:00", "00:50")
        assertThat(record.checkedInMinutesAt(date.plusDays(1).atTime(2, 0))).isEqualTo(90)
        assertThat(record.isCurrentlyInsideAt(date.plusDays(1).atTime(2, 0))).isFalse()
    }

    @Test fun `a post-midnight re-entry is not attributed to the preceding calendar date`() {
        val record = record("23:00", "00:30", "01:00")
        val now = date.plusDays(1).atTime(2, 0)
        assertThat(record.checkedInMinutesAt(now)).isEqualTo(90)
        assertThat(record.isCurrentlyInsideAt(now)).isTrue()
    }

    @Test fun `the physical state of a new entry has its own bounded expiry`() {
        val record = record("23:00", "00:30", "01:00")
        val followingMorning = date.plusDays(1).atTime(6, 0)
        assertThat(record.checkedInMinutesAt(followingMorning)).isEqualTo(90)
        assertThat(record.isCurrentlyInsideAt(followingMorning)).isTrue()
        assertThat(record.isCurrentlyInsideAt(date.plusDays(2).atTime(5, 0))).isFalse()
    }

    @Test fun `summary duration remains the fallback when clock times are absent`() {
        assertThat(AttendanceRecord(date, durationText = "02:15:30")
            .checkedInMinutesAt(afterMidnight)).isEqualTo(135)
        assertThat(AttendanceRecord(date).checkedInMinutesAt(afterMidnight)).isNull()
        assertThat(AttendanceRecord(date).isCurrentlyInsideAt(afterMidnight)).isFalse()
    }

    @Test fun `a future open swipe cannot declare the user on site`() {
        val record = record("23:00")
        assertThat(record.checkedInMinutesAt(date.atTime(22, 0))).isEqualTo(0)
        assertThat(record.isCurrentlyInsideAt(date.atTime(22, 0))).isFalse()
    }
}
