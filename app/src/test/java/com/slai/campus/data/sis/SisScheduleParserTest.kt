package com.slai.campus.data.sis

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.domain.schedule.ScheduleSource
import com.slai.campus.domain.schedule.Semester
import org.junit.Test
import java.time.LocalDate

/**
 * The contract these tests pin down is the one the design plan states as a rule:
 * **"解析失败"绝不能等价于"今天没有课"**.
 *
 * Every malformed payload must produce a typed failure; only a genuinely well-formed response with
 * zero rows may produce an empty (but successful) timetable.
 */
class SisScheduleParserTest {

    private val semester = Semester(
        academicYear = "2026",
        termCode = "3",
        firstWeekMonday = LocalDate.of(2026, 9, 7)
    )

    private fun parse(body: String?) = SisScheduleParser.parse(
        body = body,
        semester = semester,
        source = ScheduleSource.SIS_NATIVE,
        accountHash = "acct"
    )

    private val validPayload = """
        {
          "kbList": [
            {
              "kcmc": "数值分析",
              "xm": "张三",
              "xqmc": "主校区",
              "cdmc": "A201",
              "zcd": "1-2周",
              "xqj": "1",
              "jcs": "1-2",
              "jxbmc": "数值分析1班",
              "kch": "MATH101",
              "xh": "20260001"
            }
          ],
          "xqjmcMap": { "1": "星期一" },
          "zsMap": { "1": "08:00", "2": "08:55" }
        }
    """.trimIndent()

    @Test
    fun `parses a valid payload`() {
        val result = parse(validPayload)
        assertThat(result).isInstanceOf(RemoteResult.Success::class.java)
        val parsed = (result as RemoteResult.Success).data
        assertThat(parsed.rawItemCount).isEqualTo(1)
        assertThat(parsed.occurrences).hasSize(2) // weeks 1 and 2
        assertThat(parsed.occurrences.first().courseName).isEqualTo("数值分析")
        assertThat(parsed.studentId).isEqualTo("20260001")
    }

    @Test
    fun `unknown extra fields do not break parsing`() {
        val withExtra = validPayload.replace(
            "\"xh\": \"20260001\"",
            "\"xh\": \"20260001\", \"brandNewField\": {\"a\": 1}, \"another\": [1,2,3]"
        )
        assertThat(parse(withExtra)).isInstanceOf(RemoteResult.Success::class.java)
    }

    @Test
    fun `missing kbList is a schema change not an empty timetable`() {
        val result = parse("""{"xqjmcMap":{"1":"星期一"}}""")
        assertThat(result).isInstanceOf(RemoteResult.SchemaChanged::class.java)
        assertThat((result as RemoteResult.SchemaChanged).reason).contains("kbList")
    }

    @Test
    fun `empty kbList without metadata is a schema change`() {
        val result = parse("""{"kbList":[]}""")
        assertThat(result).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test
    fun `empty kbList with metadata is a legitimate empty timetable`() {
        val result = parse("""{"kbList":[],"xqjmcMap":{"1":"星期一"}}""")
        assertThat(result).isInstanceOf(RemoteResult.Success::class.java)
        assertThat((result as RemoteResult.Success).data.occurrences).isEmpty()
    }

    @Test
    fun `rows that cannot be expanded are a schema change`() {
        // Weekday and period are missing on every row: nothing can be placed on a calendar.
        val result = parse(
            """{"kbList":[{"kcmc":"某课"}],"xqjmcMap":{"1":"星期一"}}"""
        )
        assertThat(result).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test
    fun `unparsable json is a schema change`() {
        assertThat(parse("{not json")).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test
    fun `json array root is a schema change`() {
        assertThat(parse("[1,2,3]")).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test
    fun `empty body is a schema change`() {
        assertThat(parse("")).isInstanceOf(RemoteResult.SchemaChanged::class.java)
        assertThat(parse(null)).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test
    fun `html login page is detected`() {
        val html = """
            <html><head><title>深圳河套学院-教务管理系统</title></head>
            <body><form action="/yjsxt/xtgl/login_slogin.html"><input name="yhm"></form></body></html>
        """.trimIndent()
        assertThat(parse(html)).isInstanceOf(RemoteResult.SchemaChanged::class.java)
        assertThat(SisScheduleParser.looksLikeLoginPage(html)).isTrue()
        assertThat(SisScheduleParser.isLoginUrl("https://sis.slai.edu.cn/yjsxt/xtgl/login_slogin.html")).isTrue()
    }

    @Test
    fun `normal page is not mistaken for a login page`() {
        assertThat(SisScheduleParser.looksLikeLoginPage("<html><body>课表</body></html>")).isFalse()
    }

    @Test
    fun `unanchored semester fails loudly instead of guessing dates`() {
        val result = SisScheduleParser.parse(
            body = validPayload,
            semester = Semester(academicYear = "2026", termCode = "3"),
            source = ScheduleSource.SIS_NATIVE,
            accountHash = "acct"
        )
        assertThat(result).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }
}
