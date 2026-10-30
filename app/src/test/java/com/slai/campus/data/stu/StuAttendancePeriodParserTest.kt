package com.slai.campus.data.stu

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import org.junit.Test
import java.time.LocalDate

class StuAttendancePeriodParserTest {
    private val november = """{"success":true,"data":[
        {"range":"2026-10-26,2026-11-01","label":"第1周"},
        {"range":"2026-11-02,2026-11-08","label":"第2周"},
        {"range":"2026-11-09,2026-11-15","label":"第3周"},
        {"range":"2026-11-16,2026-11-22","label":"第4周"},
        {"range":"2026-11-23,2026-11-29","label":"第5周"}]}"""

    @Test fun `complete school weeks define november before the calendar month begins`() {
        val metadata = (StuAttendancePeriodParser.parse(november, "2026-11") as RemoteResult.Success).data
        assertThat(metadata.period?.start).isEqualTo(LocalDate.of(2026, 10, 26))
        assertThat(metadata.period?.endInclusive).isEqualTo(LocalDate.of(2026, 11, 29))
        assertThat(metadata.weeks).hasSize(5)
        assertThat(metadata.weeks.flatMap { it.records }).isEmpty()
    }

    @Test fun `cached week metadata keeps full future range and labels without daily records`() {
        val metadata = (StuAttendancePeriodParser.parse(november, "2026-11") as RemoteResult.Success).data
        assertThat(StuAttendancePeriodParser.decode(StuAttendancePeriodParser.encode(metadata), "2026-11"))
            .isEqualTo(metadata)
    }

    @Test fun `empty week list is confirmed absence rather than a fabricated calendar month`() {
        val result = StuAttendancePeriodParser.parse("""{"success":true,"data":[]}""", "2026-12") as RemoteResult.Success
        assertThat(result.data.period).isNull()
        assertThat(result.data.weeks).isEmpty()
    }

    @Test fun `missing success malformed ranges truncated weeks and duplicate weeks are rejected`() {
        listOf(
            """{"data":[]}""",
            """{"success":false,"data":[]}""",
            """{"success":true,"data":null}""",
            november.replace("2026-11-01", "invalid"),
            november.replace("2026-11-01", "2026-10-31"),
            november.replace("2026-11-02,2026-11-08", "2026-10-26,2026-11-01"),
            november.replace("2026-11-09,2026-11-15", "2026-12-07,2026-12-13")
        ).forEach { body ->
            assertThat(StuAttendancePeriodParser.parse(body, "2026-11"))
                .isInstanceOf(RemoteResult.SchemaChanged::class.java)
        }
    }

    @Test fun `untrusted old cache and wrong month cannot establish a school period`() {
        assertThat(StuAttendancePeriodParser.decode(null, "2026-11")).isNull()
        assertThat(StuAttendancePeriodParser.decode("broken", "2026-11")).isNull()
        assertThat(StuAttendancePeriodParser.parse(november, "2026-99"))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }
}
