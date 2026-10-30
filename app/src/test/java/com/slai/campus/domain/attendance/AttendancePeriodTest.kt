package com.slai.campus.domain.attendance

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class AttendancePeriodTest {
    private fun date(value: String) = LocalDate.parse(value)
    private fun weeks(start: String, count: Int): List<Pair<LocalDate, LocalDate>> =
        List(count) { offset -> date(start).plusWeeks(offset.toLong()).let { it to it.plusDays(6) } }

    @Test fun `school month can begin before its calendar month`() {
        val october = attendancePeriod("2026-10", weeks("2026-09-28", 4))!!
        val november = attendancePeriod("2026-11", weeks("2026-10-26", 5))!!
        assertThat(october.start).isEqualTo(date("2026-09-28"))
        assertThat(october.endInclusive).isEqualTo(date("2026-10-25"))
        assertThat(currentAttendancePeriod(date("2026-10-25"), listOf(october, november))).isEqualTo(october)
        assertThat(currentAttendancePeriod(date("2026-10-26"), listOf(october, november))).isEqualTo(november)
        assertThat(currentAttendancePeriod(date("2026-10-30"), listOf(october, november))).isEqualTo(november)
    }

    @Test fun `calendar month opening can still belong to the preceding school month`() {
        // These are supplied school ranges, not a locally inferred rule about week ownership.
        val may = attendancePeriod("2026-05", weeks("2026-05-04", 4))!!
        val june = attendancePeriod("2026-06", weeks("2026-06-01", 5))!!
        assertThat(currentAttendancePeriod(date("2026-05-31"), listOf(may, june))).isEqualTo(may)
        assertThat(currentAttendancePeriod(date("2026-06-01"), listOf(may, june))).isEqualTo(june)
        val delayedJune = attendancePeriod("2026-06", weeks("2026-06-08", 4))!!
        val extendedMay = attendancePeriod("2026-05", weeks("2026-05-04", 5))!!
        assertThat(currentAttendancePeriod(date("2026-06-01"), listOf(extendedMay, delayedJune))).isEqualTo(extendedMay)
    }

    @Test fun `year change uses school ranges rather than numeric calendar month`() {
        val january = attendancePeriod("2027-01", weeks("2026-12-28", 5))!!
        assertThat(currentAttendancePeriod(date("2026-12-31"), listOf(january))).isEqualTo(january)
        assertThat(currentAttendancePeriod(date("2027-01-01"), listOf(january))).isEqualTo(january)
    }

    @Test fun `five oclock determines the attendance day before choosing its statistical month`() {
        val october = attendancePeriod("2026-10", weeks("2026-09-28", 4))!!
        val november = attendancePeriod("2026-11", weeks("2026-10-26", 5))!!
        val periods = listOf(october, november)
        assertThat(currentAttendancePeriod(attendanceDate(date("2026-10-26").atTime(4, 59)), periods)).isEqualTo(october)
        assertThat(currentAttendancePeriod(attendanceDate(date("2026-10-26").atTime(5, 0)), periods)).isEqualTo(november)
    }

    @Test fun `full period queries retain preceding entry and following overnight exit`() {
        val november = attendancePeriod("2026-11", weeks("2026-10-26", 5))!!
        assertThat(attendancePeriodPunchRange(november))
            .isEqualTo(date("2026-10-25") to date("2026-11-30"))
    }

    @Test fun `an empty response or a calendar-shaped incomplete week cannot establish a school period`() {
        assertThat(attendancePeriod("2026-11", emptyList())).isNull()
        assertThat(attendancePeriod("2026-11", listOf(date("2026-11-01") to date("2026-11-07")))).isNull()
        assertThat(attendancePeriod("2026-11", listOf(date("2026-10-26") to date("2026-10-30")))).isNull()
        assertThat(attendancePeriod("2026-11", listOf(date("2026-10-26") to date("2026-11-02")))).isNull()
    }

    @Test fun `missing overlapping or duplicated school weeks are rejected`() {
        val complete = weeks("2026-10-26", 5)
        assertThat(attendancePeriod("2026-11", complete.filterIndexed { index, _ -> index != 2 })).isNull()
        assertThat(attendancePeriod("2026-11", complete + complete.first())).isNull()
        assertThat(attendancePeriod("2026-11", listOf(complete.first(), date("2026-10-27") to date("2026-11-02")))).isNull()
    }

    @Test fun `week order does not change the verified period`() {
        val complete = weeks("2026-10-26", 5)
        assertThat(attendancePeriod("2026-11", complete.reversed())).isEqualTo(attendancePeriod("2026-11", complete))
    }

    @Test fun `invalid month identifiers cannot be cached as a period`() {
        listOf("2026-13", "2026-00", "2026-1", "26-11", "2026-11-extra").forEach { month ->
            assertThat(attendancePeriod(month, weeks("2026-10-26", 5))).isNull()
        }
    }

    @Test fun `obviously mismatched month year or overlong response cannot establish a period`() {
        assertThat(attendancePeriod("2026-11", weeks("2025-10-27", 5))).isNull()
        assertThat(attendancePeriod("2026-11", weeks("2026-08-31", 4))).isNull()
        assertThat(attendancePeriod("2026-11", weeks("2026-10-26", 7))).isNull()
        // The resolver examines adjacent calendar months; do not accept a range two months away.
        assertThat(attendancePeriod("2026-03", weeks("2026-03-30", 6))).isNull()
        assertThat(attendancePeriod("2026-05", weeks("2026-03-30", 6))).isNull()
        // A six-week school response remains supported; no local fixed day decides its bounds.
        assertThat(attendancePeriod("2026-11", weeks("2026-10-26", 6))).isNotNull()
    }

    @Test fun `ambiguous ranges cannot silently choose a different month`() {
        val october = attendancePeriod("2026-10", weeks("2026-09-28", 5))!!
        val november = attendancePeriod("2026-11", weeks("2026-10-26", 5))!!
        assertThat(currentAttendancePeriod(date("2026-10-30"), listOf(october, november))).isNull()
        assertThat(currentAttendancePeriod(date("2026-12-01"), listOf(october, november))).isNull()
        assertThat(currentAttendancePeriod(date("2026-10-30"), listOf(november, november))).isEqualTo(november)
    }
}
