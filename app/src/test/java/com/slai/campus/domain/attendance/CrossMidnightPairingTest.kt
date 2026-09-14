package com.slai.campus.domain.attendance

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CrossMidnightPairingTest {
    private fun punch(time: String, direction: PunchDirection) = AttendancePunch(
        id = time + direction, time = LocalDateTime.parse(time), direction = direction, place = "教学楼"
    )
    private val day = LocalDate.of(2026, 1, 10)
    private val now = LocalDateTime.parse("2026-01-13T12:00:00")

    @Test fun `next-day exit closes the preceding evening entry`() {
        val data = PunchPairing.daily(listOf(
            punch("2026-01-10T16:00:00", PunchDirection.IN),
            punch("2026-01-11T00:30:00", PunchDirection.OUT)
        ), now)
        val evening = data.getValue(day)
        assertThat(evening.minutesAt(now)).isEqualTo(510)
        assertThat(evening.sessions.single().to).isEqualTo(LocalDateTime.parse("2026-01-11T00:30:00"))
        assertThat(evening.hasDiscardedSessionAt(now)).isFalse()
        assertThat(data[day.plusDays(1)]?.minutesAt(now) ?: 0).isEqualTo(0)
    }

    @Test fun `morning entry is not combined with an expired entry from the previous day`() {
        val data = PunchPairing.daily(listOf(
            punch("2026-01-10T16:00:00", PunchDirection.IN),
            punch("2026-01-11T09:00:00", PunchDirection.IN),
            punch("2026-01-11T12:00:00", PunchDirection.OUT)
        ), now)
        assertThat(data.getValue(day).hasDiscardedSessionAt(now)).isTrue()
        assertThat(data.getValue(day).minutesAt(now)).isEqualTo(0)
        assertThat(data.getValue(day.plusDays(1)).minutesAt(now)).isEqualTo(180)
    }

    @Test fun `seconds are summed before converting the daily total to minutes`() {
        val data = PunchPairing.of(day, listOf(
            punch("2026-01-10T09:00:00", PunchDirection.IN),
            punch("2026-01-10T10:00:40", PunchDirection.OUT),
            punch("2026-01-10T11:00:00", PunchDirection.IN),
            punch("2026-01-10T12:00:40", PunchDirection.OUT)
        ), now)
        assertThat(data.minutesAt(now)).isEqualTo(121)
    }

    @Test fun `after-midnight re-entry belongs to the new date without double counting the earlier stay`() {
        val punches = listOf(
            punch("2026-01-10T23:00:00", PunchDirection.IN),
            punch("2026-01-11T00:30:00", PunchDirection.OUT),
            punch("2026-01-11T01:00:00", PunchDirection.IN),
            punch("2026-01-11T02:00:00", PunchDirection.OUT)
        )
        val data = PunchPairing.daily(punches, now)
        assertThat(data.getValue(day).minutesAt(now)).isEqualTo(90)
        assertThat(data.getValue(day.plusDays(1)).minutesAt(now)).isEqualTo(60)
        assertThat(PunchPairing.of(day, punches, now).minutesAt(now)).isEqualTo(90)
    }

    @Test fun `month boundary exit closes the last calendar day`() {
        val date = LocalDate.of(2026, 1, 31)
        val at = LocalDateTime.parse("2026-02-02T12:00:00")
        val data = PunchPairing.daily(listOf(
            punch("2026-01-31T23:00:00", PunchDirection.IN),
            punch("2026-02-01T00:30:00", PunchDirection.OUT)
        ), at)
        assertThat(data.getValue(date).minutesAt(at)).isEqualTo(90)
    }

    @Test fun `an exit exactly at the cutoff closes the stay but a later exit does not`() {
        fun paired(exit: String) = PunchPairing.of(day, listOf(
            punch("2026-01-10T23:00:00", PunchDirection.IN),
            punch(exit, PunchDirection.OUT)
        ), now)
        assertThat(paired("2026-01-11T05:00:00").minutesAt(now)).isEqualTo(360)
        assertThat(paired("2026-01-11T05:00:01").hasDiscardedSessionAt(now)).isTrue()
        assertThat(paired("2026-01-11T05:00:01").minutesAt(now)).isEqualTo(0)
    }

    @Test fun `closed overnight session shows next-day exit instead of on-site or discarded`() {
        val session = PunchSession(day.atTime(23, 0), day.plusDays(1).atTime(0, 30))
        assertThat(session.textAt(now, java.util.Locale.CHINA)).isEqualTo("23:00 → 次日 00:30")
        assertThat(session.textAt(now, java.util.Locale.ENGLISH)).isEqualTo("23:00 → Next day 00:30")
    }
}
