package com.slai.campus.data.schedule

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.data.sis.SisScheduleParser
import com.slai.campus.domain.schedule.ClassOccurrence
import com.slai.campus.domain.schedule.ScheduleSource
import com.slai.campus.domain.schedule.Semester
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ScheduleCacheIdentityTest {
    private val occurrence = ClassOccurrence("temporary|parser-id", "测试课程", date = LocalDate.of(2026,10,12),
        startTime = LocalTime.of(9,30), endTime = LocalTime.of(10,15), source = ScheduleSource.SIS_NATIVE, periodStart = 1)

    @Test fun `first sync and repeat sync generate the same id for the verified owner`() {
        assertThat(occurrence.accountCacheId("verified-owner"))
            .isEqualTo(occurrence.copy(id="another-temporary-id").accountCacheId("verified-owner"))
    }

    @Test fun `different full owner hashes cannot collide on their first eight characters`() {
        assertThat(occurrence.accountCacheId("samehead-account-A"))
            .isNotEqualTo(occurrence.accountCacheId("samehead-account-B"))
    }

    @Test fun `a trusted empty timetable identifies the student from the profile`() {
        val body = """{"kbList":[],"xqjmcMap":{"1":"星期一"},"xsxx":{"XH":"test-student-A","XNM":"2026","XQM":"3"}}"""
        val result = SisScheduleParser.parse(body, Semester("2026","3",firstWeekMonday=LocalDate.of(2026,9,7)),
            ScheduleSource.SIS_NATIVE, "temporary") as RemoteResult.Success
        assertThat(result.data.studentId).isEqualTo("test-student-A")
        assertThat(result.data.trustedEmpty).isTrue()
    }
}
