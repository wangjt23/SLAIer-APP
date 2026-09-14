package com.slai.campus.data.stu

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.domain.attendance.AttendanceStats
import org.junit.Test

/** Synthetic statistics exercise the exact field mappings used by the school's attendList page. */
class StuAttendanceStatsTest {
    @Test fun `remaining opportunities subtract cross-week usage instead of displaying the allowance`() {
        val body = """{
            "success":true,"weeks":[],"data":{},
            "stats":{"totalValidPunches":11,"actualWorkdayPunches":10,
                "actualRestdayPunches":1,"maxAllowedRestdayPunches":3,"crossWeekUsedPunches":1}
        }"""
        val month = (StuAttendanceParser.parse(body, "2026-09") as RemoteResult.Success).data
        assertThat(month.summary.effectiveDays).isEqualTo(11)
        assertThat(month.summary.crossWeekUsedPunches).isEqualTo(1)
        assertThat(month.summary.remainingMakeupDays).isEqualTo(2)
        // The repository persists stats as strings, then uses this same mapper to restore them.
        assertThat(StuAttendanceParser.toSummary(month.stats).remainingMakeupDays).isEqualTo(2)
    }

    @Test fun `valid attendance uses the server total even when components disagree`() {
        val stats = StuAttendanceParser.toSummary(mapOf(
            "totalValidPunches" to "11", "actualWorkdayPunches" to "8", "actualRestdayPunches" to "5"
        ))
        assertThat(stats.effectiveDays).isEqualTo(11)
    }

    @Test fun `no cross-week usage leaves all three opportunities`() {
        assertThat(AttendanceStats(crossWeekUsedPunches = 0).remainingMakeupDays).isEqualTo(3)
    }

    @Test fun `exhausted allowance never becomes negative`() {
        listOf(3, 4).forEach {
            assertThat(AttendanceStats(crossWeekUsedPunches = it).remainingMakeupDays).isEqualTo(0)
        }
    }

    @Test fun `restday counts and old allowance fields cannot substitute for cross-week usage`() {
        val stats = AttendanceStats(maxAllowedRestdayPunches = 9, restdayPunches = 7, crossWeekUsedPunches = 1)
        assertThat(stats.remainingMakeupDays).isEqualTo(2)
    }

    @Test fun `missing server fields remain unknown instead of inventing counts`() {
        val stats = AttendanceStats(actualWorkdayPunches = 10, restdayPunches = 1, maxAllowedRestdayPunches = 3)
        assertThat(stats.effectiveDays).isNull()
        assertThat(stats.remainingMakeupDays).isNull()
    }

    @Test fun `malformed or negative usage cannot increase the allowance`() {
        assertThat(StuAttendanceParser.toSummary(mapOf("crossWeekUsedPunches" to "unknown")).remainingMakeupDays).isNull()
        assertThat(AttendanceStats(crossWeekUsedPunches = -1).remainingMakeupDays).isNull()
    }

    @Test fun `new statistics alone are enough to show the summary`() {
        assertThat(AttendanceStats(totalValidPunches = 11).hasAnything).isTrue()
        assertThat(AttendanceStats(crossWeekUsedPunches = 1).hasAnything).isTrue()
    }
}
