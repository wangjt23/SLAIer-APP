package com.slai.campus.data.provider

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.web.CaptureRecord
import com.slai.campus.domain.provider.ApiProvider
import com.slai.campus.domain.provider.Fields
import com.slai.campus.domain.provider.ProviderPurpose
import com.slai.campus.domain.schedule.ScheduleSource
import com.slai.campus.domain.schedule.Semester
import org.junit.Test
import java.time.LocalDate

private val semester = Semester(
    academicYear = "2026",
    termCode = "3",
    firstWeekMonday = LocalDate.of(2026, 9, 7)
)

private fun provider(
    rowsPath: String = "kbList",
    fieldMap: Map<String, String> = emptyMap()
) = ApiProvider(
    id = "test",
    name = "test",
    system = SchoolSystem.SIS.key,
    purpose = ProviderPurpose.TIMETABLE.key,
    method = "POST",
    url = "https://sis.slai.edu.cn/yjsxt/xskbcx/xskbcx_cxXsKb.html?gnmkdm=N253508",
    body = "xnm={{xnm}}&xqm={{xqm}}&kzlx=ck",
    rowsPath = rowsPath,
    fieldMap = fieldMap
)

/**
 * The provider layer exists because the first version's hard-coded endpoints were wrong. These tests
 * pin down that a provider is honoured exactly as written, and that a payload which does not match is
 * a typed failure rather than an empty timetable.
 */
class GenericTimetableParserTest {

    private val zfsoftPayload = """
        {
          "kbList": [
            {
              "kcmc": "数值分析",
              "xm": "张三",
              "cdmc": "A201",
              "xqmc": "主校区",
              "zcd": "1-2周",
              "xqj": "1",
              "jcs": "1-2",
              "kch": "MATH101",
              "jxbmc": "数值分析1班",
              "xh": "20260001"
            }
          ],
          "xqjmcMap": {"1": "星期一"},
          "zsMap": {"1": "08:00", "2": "08:55"}
        }
    """.trimIndent()

    private fun parse(body: String?, p: ApiProvider = provider()) = GenericTimetableParser.parse(
        body = body,
        provider = p,
        semester = semester,
        accountHash = "acct",
        source = ScheduleSource.SIS_PROVIDER
    )

    /**
     * An empty array at `rowsPath` is reported as a **zero-row success**, and that is exactly the
     * payload that used to destroy the cache: `ScheduleRepositoryImpl.persist` replaced all rows with
     * nothing, then reported 「已同步」, and the week view went empty with no error anywhere.
     *
     * This test pins the parser's contract so the repository-side guard cannot be "simplified away":
     * a zero-row Success is *not* proof that the timetable is empty.
     */
    @Test
    fun `an empty rows array is a zero-row success, not proof of an empty timetable`() {
        val result = parse("""{"kbList":[]}""")
        assertThat(result).isInstanceOf(RemoteResult.Success::class.java)
        val data = (result as RemoteResult.Success).data
        assertThat(data.occurrences).isEmpty()
        assertThat(data.rawItemCount).isEqualTo(0)
    }


    @Test
    fun `parses a zfsoft-shaped payload with default field mapping`() {
        val result = parse(zfsoftPayload)
        assertThat(result).isInstanceOf(RemoteResult.Success::class.java)
        val parsed = (result as RemoteResult.Success).data
        assertThat(parsed.occurrences).hasSize(2) // weeks 1 and 2
        assertThat(parsed.occurrences.first().courseName).isEqualTo("数值分析")
        assertThat(parsed.occurrences.first().teacher).isEqualTo("张三")
        assertThat(parsed.occurrences.first().location).isEqualTo("A201")
        assertThat(parsed.occurrences.first().source).isEqualTo(ScheduleSource.SIS_PROVIDER)
        assertThat(parsed.studentId).isEqualTo("20260001")
    }

    @Test
    fun `honours a nested rowsPath`() {
        val nested = """{"code":0,"data":{"list":[{"kcmc":"机器学习","zcd":"1周","xqj":"2","jcs":"3-4"}]}}"""
        val result = parse(nested, provider(rowsPath = "data.list"))
        assertThat(result).isInstanceOf(RemoteResult.Success::class.java)
        assertThat((result as RemoteResult.Success).data.occurrences).hasSize(1)
    }

    @Test
    fun `accepts a root array when rowsPath is empty`() {
        val root = """[{"kcmc":"英语","zcd":"1周","xqj":"3","jcs":"5-6"}]"""
        val result = parse(root, provider(rowsPath = ""))
        assertThat(result).isInstanceOf(RemoteResult.Success::class.java)
    }

    @Test
    fun `honours a custom field mapping`() {
        val custom = """{"rows":[{"course":"线性代数","weeks":"1周","day":"1","period":"1-2"}]}"""
        val result = parse(
            custom,
            provider(
                rowsPath = "rows",
                fieldMap = mapOf(
                    Fields.COURSE_NAME to "course",
                    Fields.WEEKS to "weeks",
                    Fields.WEEKDAY to "day",
                    Fields.PERIODS to "period"
                )
            )
        )
        assertThat(result).isInstanceOf(RemoteResult.Success::class.java)
        assertThat((result as RemoteResult.Success).data.occurrences.single().courseName)
            .isEqualTo("线性代数")
    }

    @Test
    fun `missing rows path is a schema change`() {
        val result = parse("""{"somethingElse":[]}""")
        assertThat(result).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test
    fun `empty recognised array is a genuine empty timetable`() {
        val result = parse("""{"kbList":[]}""")
        assertThat(result).isInstanceOf(RemoteResult.Success::class.java)
        assertThat((result as RemoteResult.Success).data.occurrences).isEmpty()
    }

    @Test
    fun `html response is a schema change`() {
        assertThat(parse("<html><body>login</body></html>"))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test
    fun `rows that cannot be placed on a calendar are a schema change`() {
        // No weekday / period / week info at all.
        val result = parse("""{"kbList":[{"kcmc":"某课"}]}""")
        assertThat(result).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test
    fun `unanchored semester fails instead of inventing dates`() {
        val result = GenericTimetableParser.parse(
            body = zfsoftPayload,
            provider = provider(),
            semester = Semester(academicYear = "2026", termCode = "3"),
            accountHash = "acct",
            source = ScheduleSource.SIS_PROVIDER
        )
        assertThat(result).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }
}

class ProviderLearnerTest {

    private fun record(
        method: String = "POST",
        url: String = "https://sis.slai.edu.cn/yjsxt/xskbcx/xskbcx_cxXsKb.html?gnmkdm=N253508",
        requestBody: String? = "xnm=2026&xqm=3&kzlx=ck",
        body: String
    ) = CaptureRecord(
        method = method,
        url = url,
        requestBody = requestBody,
        status = 200,
        body = body,
        pageUrl = "https://sis.slai.edu.cn/yjsxt/xskbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N253508"
    )

    @Test
    fun `recognises a zfsoft timetable payload and derives the mapping`() {
        val payload = """{"kbList":[{"kcmc":"数值分析","xm":"张三","cdmc":"A201","zcd":"1-16周","xqj":"1","jcs":"1-2"}],"zsMap":{"1":"08:00"}}"""
        val result = ProviderLearner.analyze(listOf(record(body = payload)), SchoolSystem.SIS)

        val best = result.best
        assertThat(best).isNotNull()
        assertThat(best!!.provider.rowsPath).isEqualTo("kbList")
        assertThat(best.provider.method).isEqualTo("POST")
        assertThat(best.provider.url).contains("xskbcx_cxXsKb.html")
        assertThat(best.provider.fieldMap[Fields.COURSE_NAME]).isEqualTo("kcmc")
        assertThat(best.provider.fieldMap[Fields.WEEKDAY]).isEqualTo("xqj")
        assertThat(best.provider.fieldMap[Fields.WEEKS]).isEqualTo("zcd")
        assertThat(best.provider.isLearned).isTrue()
        assertThat(best.rowCount).isEqualTo(1)
    }

    @Test
    fun `templatizes the captured term so the provider survives next semester`() {
        val payload = """{"kbList":[{"kcmc":"课","zcd":"1周","xqj":"1","jcs":"1-2"}]}"""
        val best = ProviderLearner.analyze(listOf(record(body = payload)), SchoolSystem.SIS).best
        assertThat(best!!.provider.body).isEqualTo("xnm={{xnm}}&xqm={{xqm}}&kzlx=ck")
    }

    @Test
    fun `ignores json that is not a timetable`() {
        val userInfo = """{"user":{"name":"张三","id":"20260001","roles":["student"]}}"""
        val result = ProviderLearner.analyze(listOf(record(body = userInfo)), SchoolSystem.SIS)
        assertThat(result.best).isNull()
        assertThat(result.note).contains("没有")
    }

    @Test
    fun `ignores non-json bodies`() {
        val result = ProviderLearner.analyze(
            listOf(record(body = "<html><body>ok</body></html>")),
            SchoolSystem.SIS
        )
        assertThat(result.best).isNull()
    }

    @Test
    fun `empty capture is reported clearly`() {
        val result = ProviderLearner.analyze(emptyList(), SchoolSystem.SIS)
        assertThat(result.best).isNull()
        assertThat(result.note).contains("没有捕获到")
    }

    @Test
    fun `picks the richest candidate when several look like timetables`() {
        val small = """{"kbList":[{"kcmc":"A","zcd":"1周","xqj":"1","jcs":"1-2"}]}"""
        val large = """{"kbList":[
            {"kcmc":"A","zcd":"1周","xqj":"1","jcs":"1-2","cdmc":"X","xm":"T","kch":"K","jxbmc":"C"},
            {"kcmc":"B","zcd":"1周","xqj":"2","jcs":"3-4","cdmc":"Y","xm":"T","kch":"K","jxbmc":"C"}
        ]}"""
        val result = ProviderLearner.analyze(
            listOf(record(body = small), record(body = large)),
            SchoolSystem.SIS
        )
        assertThat(result.candidates).hasSize(2)
        assertThat(result.best!!.rowCount).isEqualTo(2)
    }
}

class ApiProviderTest {

    private fun base() = ApiProvider(
        id = "p1",
        name = "课表",
        system = "sis",
        purpose = "timetable",
        method = "POST",
        url = "https://sis.slai.edu.cn/x",
        body = "a=1",
        rowsPath = "kbList"
    )

    @Test
    fun `a well formed provider validates`() {
        assertThat(base().validate()).isEmpty()
        assertThat(base().isValid).isTrue()
    }

    @Test
    fun `rejects an unknown system`() {
        assertThat(base().copy(system = "moodle").validate()).isNotEmpty()
    }

    @Test
    fun `rejects an unknown purpose`() {
        assertThat(base().copy(purpose = "grades").validate()).isNotEmpty()
    }

    @Test
    fun `rejects a non-http url`() {
        assertThat(base().copy(url = "file:///etc/passwd").validate()).isNotEmpty()
    }

    @Test
    fun `requires a body for POST`() {
        assertThat(base().copy(body = null).validate()).isNotEmpty()
    }

    @Test
    fun `requires a course name mapping for timetables`() {
        val noName = base().copy(fieldMap = mapOf(Fields.COURSE_NAME to ""))
        assertThat(noName.validate()).isNotEmpty()
    }

    @Test
    fun `default field map fills the gaps`() {
        val resolved = base().copy(fieldMap = mapOf(Fields.COURSE_NAME to "custom")).resolvedFieldMap()
        assertThat(resolved[Fields.COURSE_NAME]).isEqualTo("custom")
        assertThat(resolved[Fields.TEACHER]).isEqualTo("xm")
    }

    @Test
    fun `json round trips`() {
        val original = base()
        val restored = ApiProvider.fromJson(ApiProvider.toJson(original)).getOrThrow()
        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `malformed json fails cleanly`() {
        assertThat(ApiProvider.fromJson("{not json").isFailure).isTrue()
    }
}
