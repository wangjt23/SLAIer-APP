package com.slai.campus.domain.attendance

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/** Qualified days are school verdicts, not a locally imposed five-day accounting cap. */
class AttendanceWeekCountTest {
    private val monday = LocalDate.of(2026, 9, 7)
    private fun week(vararg qualified: Boolean?) = AttendanceWeek(
        label = "week",
        range = "$monday,${monday.plusDays(6)}",
        records = qualified.mapIndexed { index, value ->
            AttendanceRecord(date = monday.plusDays(index.toLong()), qualified = value)
        }
    )

    @Test fun `a full week keeps all seven school-qualified days`() {
        assertThat(week(true, true, true, true, true, true, true).qualifiedCount).isEqualTo(7)
    }

    @Test fun `the sixth qualified day is not discarded because it can contribute across weeks`() {
        assertThat(week(true, true, true, true, true, false, true).qualifiedCount).isEqualTo(6)
    }

    @Test fun `unknown and unqualified days never count as qualified`() {
        assertThat(week(true, false, null, true, null, false, true).qualifiedCount).isEqualTo(3)
    }

    @Test fun `cross-month days remain part of their school attendance week`() {
        val start = LocalDate.of(2026, 8, 31)
        val week = AttendanceWeek("week", "$start,${start.plusDays(6)}", (0L..5L).map {
            AttendanceRecord(date = start.plusDays(it), qualified = true)
        })
        assertThat(week.qualifiedCount).isEqualTo(6)
    }
}
