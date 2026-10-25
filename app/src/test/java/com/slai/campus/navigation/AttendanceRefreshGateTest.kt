package com.slai.campus.navigation

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class AttendanceRefreshGateTest {
    private val date = LocalDate.of(2026, 10, 24)

    @Test fun `first foreground refreshes and short tab transitions reuse that request`() {
        val gate = AttendanceRefreshGate()
        assertThat(gate.claim(1, date, 0, false)).isTrue()
        assertThat(gate.claim(1, date, 59_999, false)).isFalse()
        assertThat(gate.claim(1, date, 60_000, false)).isTrue()
    }

    @Test fun `recovered login forces new attendance even within the normal interval`() {
        val gate = AttendanceRefreshGate()
        gate.claim(1, date, 0, false)
        assertThat(gate.claim(1, date, 1, true)).isTrue()
    }

    @Test fun `account and attendance date changes each require a new fetch`() {
        val gate = AttendanceRefreshGate()
        gate.claim(1, date, 0, false)
        assertThat(gate.claim(2, date, 1, false)).isTrue()
        assertThat(gate.claim(2, date.plusDays(1), 2, false)).isTrue()
    }

    @Test fun `moving wall clock backwards does not block future foreground refreshes`() {
        val gate = AttendanceRefreshGate()
        gate.claim(1, date, 100_000, false)
        assertThat(gate.claim(1, date, 90_000, false)).isTrue()
        assertThat(gate.claim(1, date, 90_001, false)).isFalse()
    }
}
