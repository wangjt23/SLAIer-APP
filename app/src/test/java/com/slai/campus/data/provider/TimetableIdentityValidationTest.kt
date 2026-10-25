package com.slai.campus.data.provider

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.data.sis.SisScheduleParser
import com.slai.campus.domain.provider.ApiProvider
import com.slai.campus.domain.provider.Fields
import com.slai.campus.domain.schedule.ScheduleSource
import com.slai.campus.domain.schedule.Semester
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TimetableIdentityValidationTest {
    private val semester = Semester("2026", "3", firstWeekMonday = LocalDate.of(2026, 9, 7))
    private val defaultProvider = ApiProvider(
        id = "p", name = "课表", system = "sis", purpose = "timetable",
        url = "https://school.invalid/api", rowsPath = "kbList"
    )
    private val customProvider = defaultProvider.copy(
        fieldMap = mapOf(Fields.COURSE_NAME to "course", Fields.STUDENT_ID to "studentNo")
    )
    private fun row(owner: String? = null, ownerKey: String = "xh"): String {
        val identity = owner?.let { ",\"$ownerKey\":\"$it\"" }.orEmpty()
        return """{"kcmc":"原生课程","course":"映射课程","xqj":"1","jcs":"1-2","zcd":"1周"$identity}"""
    }

    private fun body(rows: String, profile: String = "\"XH\":\"20260001\",\"XNM\":\"2026\",\"XQM\":\"3\"", extra: String = "") =
        """{"kbList":[$rows],"xqjmcMap":{"1":"星期一"},"xsxx":{$profile}$extra}"""

    private fun parse(body: String, provider: ApiProvider = defaultProvider) =
        GenericTimetableParser.parse(body, provider, semester, "temporary", ScheduleSource.SIS_PROVIDER)

    @Test fun `learned standard provider identifies profile owner with both empty and nonempty rows`() {
        listOf("", row()).forEach { rows ->
            val data = (parse(body(rows)) as RemoteResult.Success).data
            assertThat(data.studentId).isEqualTo("20260001")
            assertThat(data.reportedTerm).isEqualTo("2026" to "3")
            assertThat(data.trustedEmpty).isEqualTo(rows.isEmpty())
        }
    }

    @Test fun `explicit default mappings retain native metadata for nonempty rows`() {
        val data = (parse(body(row(), extra = """, "zsMap":{"1":"09:15","2":"10:10"}"""),
            defaultProvider.copy(fieldMap = Fields.ZFSOFT_DEFAULTS)) as RemoteResult.Success).data
        assertThat(data.studentId).isEqualTo("20260001")
        assertThat(data.occurrences.single().startTime).isEqualTo(LocalTime.of(9, 15))
    }

    @Test fun `custom keys under kbList still take precedence over native field names`() {
        val data = (parse(body(row("20260001", "studentNo")), customProvider) as RemoteResult.Success).data
        assertThat(data.occurrences.single().courseName).isEqualTo("映射课程")
        assertThat(data.studentId).isEqualTo("20260001")
        assertThat(data.reportedTerm).isEqualTo("2026" to "3")
    }

    @Test fun `native and learned standard provider reject profile versus row owner mismatch`() {
        val payload = body(row("20260002"))
        assertThat(parse(payload)).isInstanceOf(RemoteResult.SchemaChanged::class.java)
        assertThat(SisScheduleParser.parse(payload, semester, ScheduleSource.SIS_NATIVE, "temporary"))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `generic custom mapping validates every row owner and the profile`() {
        val profileMismatch = body(row("20260002", "studentNo"))
        val mixedRows = body(row("20260001", "studentNo") + "," +
            row("20260002", "studentNo"), profile = "")
        listOf(profileMismatch, mixedRows).forEach { payload ->
            assertThat(parse(payload, customProvider)).isInstanceOf(RemoteResult.SchemaChanged::class.java)
        }
    }

    @Test fun `generic nested rows also reject mixed mapped owners`() {
        val payload = """{"data":{"rows":[${row("20260001", "studentNo")},${row("20260002", "studentNo")}]}}"""
        assertThat(parse(payload, customProvider.copy(rowsPath = "data.rows")))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `native and both provider paths reject explicit school errors even with valid rows`() {
        listOf(""", "code":500""", """, "success":false""").forEach { error ->
            val payload = body(row(), extra = error)
            listOf(defaultProvider, customProvider).forEach { provider ->
                assertThat(parse(payload, provider)).isInstanceOf(RemoteResult.SchemaChanged::class.java)
            }
            assertThat(SisScheduleParser.parse(payload, semester, ScheduleSource.SIS_NATIVE, "temporary"))
                .isInstanceOf(RemoteResult.SchemaChanged::class.java)
        }
    }

    @Test fun `same owner with whitespace is normalized and conflicting profile aliases rejected`() {
        val data = (parse(body(row(" 20260001 ", "studentNo")), customProvider) as RemoteResult.Success).data
        assertThat(data.studentId).isEqualTo("20260001")
        assertThat(parse(body(row(), profile = "\"XH\":\"20260001\", \"xh\":\"20260002\"")))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `custom mapping cannot date rows using another term anchor`() {
        assertThat(parse(body(row(), profile = "\"XH\":\"20260001\",\"XNM\":\"2025\",\"XQM\":\"3\""), customProvider))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }
}
