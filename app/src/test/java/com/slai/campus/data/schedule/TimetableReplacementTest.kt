package com.slai.campus.data.schedule

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.data.sis.SisScheduleParser
import com.slai.campus.data.provider.GenericTimetableParser
import com.slai.campus.domain.provider.ApiProvider
import com.slai.campus.domain.schedule.ScheduleSource
import com.slai.campus.domain.schedule.Semester
import org.junit.Test

class TimetableReplacementTest {
    private val semester = Semester(academicYear = "2026", termCode = "3")
    private fun parse(body: String) = SisScheduleParser.parse(body, semester, ScheduleSource.SIS_NATIVE, "account")
    private fun empty() = (parse("""{"kbList":[],"xqjmcMap":{"1":"星期一"}}""") as RemoteResult.Success).data

    @Test fun `trusted empty semester can clear outdated courses`() {
        assertThat(canReplaceTimetable(empty(), fullSemester = true)).isTrue()
    }

    @Test fun `empty provider or DOM week cannot clear full semester`() {
        assertThat(canReplaceTimetable(empty().copy(trustedEmpty = false), fullSemester = true)).isFalse()
        assertThat(canReplaceTimetable(empty(), fullSemester = false)).isFalse()
    }

    @Test fun `null list or list without metadata is never trusted empty`() {
        assertThat(parse("""{"kbList":null,"xqjmcMap":{"1":"星期一"}}"""))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
        assertThat(parse("""{"kbList":[]}"""))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `empty response from a different term cannot clear current courses`() {
        val parsed = parse("""{"kbList":[],"xqjmcMap":{"1":"星期一"},"xsxx":{"XNM":"2025","XQM":"3"}}""")
        assertThat(parsed).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `garbage rows are not equivalent to empty semester`() {
        assertThat(parse("""{"kbList":[{"kcmc":"bad"}],"xqjmcMap":{"1":"星期一"}}"""))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `error response with copied metadata cannot prove empty semester`() {
        assertThat(parse("""{"code":500,"kbList":[],"xqjmcMap":{"1":"星期一"}}"""))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
        assertThat(parse("""{"success":false,"kbList":[],"xqjmcMap":{"1":"星期一"}}"""))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `learned standard provider shares trustworthy empty validation`() {
        val provider = ApiProvider("p", "课表", "sis", "timetable", url = "https://school.invalid/api", rowsPath = "kbList")
        val parsed = GenericTimetableParser.parse(
            """{"kbList":[],"xqjmcMap":{"1":"星期一"}}""", provider, semester, "account", ScheduleSource.SIS_PROVIDER
        )
        assertThat(canReplaceTimetable((parsed as RemoteResult.Success).data, true)).isTrue()
        val generic = GenericTimetableParser.parse(
            """{"data":[]}""", provider.copy(rowsPath = "data"), semester, "account", ScheduleSource.SIS_PROVIDER
        )
        assertThat(canReplaceTimetable((generic as RemoteResult.Success).data, true)).isFalse()
    }
}
