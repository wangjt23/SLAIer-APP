package com.slai.campus.data.stu

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.session.AccountHasher
import com.slai.campus.core.session.SessionState
import org.junit.Test

class StuAttendanceCompletenessTest {
    private fun parse(body: String) = StuAttendanceParser.parse(body, "2026-10")
    private val week = """{"label":"第1周","range":"2026-10-05,2026-10-11"}"""
    private val record = """{"date":"2026-10-05","userNo":"20260001"}"""

    private fun rejected(body: String) {
        assertThat(parse(body)).isInstanceOf(RemoteResult.SchemaChanged::class.java)
        assertThat(attendanceSessionState(200, null, body, "2026-10")).isEqualTo(SessionState.ERROR)
    }

    @Test fun `week metadata without its data object cannot erase a cached month`() {
        rejected("""{"success":true,"weeks":[$week]}""")
    }

    @Test fun `every advertised week requires its explicit records array`() {
        rejected("""{"success":true,"weeks":[$week,$week],"data":{"week1":[$record]}}""")
        rejected("""{"success":true,"weeks":[$week],"data":{"week1":null}}""")
    }

    @Test fun `invalid records cannot become either an empty or a partial successful month`() {
        rejected("""{"data":{"week1":[{"date":"bad","userNo":"20260001"}]}}""")
        rejected("""{"data":{"week1":[$record,{"date":"2026-02-30"}]}}""")
        rejected("""{"data":{"week1":[$record,{}]}}""")
        rejected("""{"data":{"week1":[$record,null]}}""")
    }

    @Test fun `nonobject week metadata and nonarray week records fail closed`() {
        rejected("""{"weeks":[null],"data":{"week1":[$record]}}""")
        rejected("""{"data":{"week1":{"date":"2026-10-05"}}}""")
    }

    @Test fun `conflicting advertised and actual weeks cannot silently discard rows`() {
        rejected("""{"weeks":[$week],"data":{"week1":[$record],"week2":[{"date":"2026-10-12"}]}}""")
    }

    @Test fun `duplicate dates cannot overwrite another record while publishing a monthly snapshot`() {
        rejected("""{"data":{"week1":[$record,{"date":"2026-10-05","duration":3600}]}}""")
        rejected("""{"data":{"week1":[$record],"week2":[$record]}}""")
    }

    @Test fun `malformed container types and visibly truncated JSON are not empty months`() {
        rejected("""{"weeks":{},"data":{"week1":[$record]}}""")
        rejected("""{"weeks":[],"data":null,"success":true}""")
        rejected("""{"data":{"week1":[$record],"week2":false}}""")
        rejected("""{"data":{"week1":[$record],"week2":[]}""")
    }

    @Test fun `an unqualified empty data object does not prove a complete month`() {
        rejected("""{"data":{}}""")
        rejected("""{"weeks":[],"data":{}}""")
    }

    @Test fun `explicit successful empty month remains trusted authentication evidence`() {
        val body = """{"success":true,"weeks":[],"data":{},"stats":{"totalWorkdays":0}}"""
        val data = (parse(body) as RemoteResult.Success).data
        assertThat(data.records).isEmpty()
        assertThat(data.summary?.totalWorkdays).isEqualTo(0)
        assertThat(attendanceSessionState(200, null, body, "2026-10")).isEqualTo(SessionState.AUTHENTICATED)
    }

    @Test fun `data only deployment and explicitly empty weekly arrays remain supported`() {
        val body = """{"data":{"week2":[],"week1":[$record]}}"""
        val data = (parse(body) as RemoteResult.Success).data
        assertThat(data.records).hasSize(1)
        assertThat(data.weeks.map { it.label }).containsExactly("week1", "week2").inOrder()
        assertThat(parse("""{"weeks":[$week],"data":{"week1":[]},"success":true}"""))
            .isInstanceOf(RemoteResult.Success::class.java)
    }

    @Test fun `unknown fields and auxiliary arrays do not alter record identity`() {
        val body = """{"weeks":[$week],"data":{"week1":[$record],"auxiliary":[{"userNo":"another-user"}]},"newField":{"value":1}}"""
        val data = (parse(body) as RemoteResult.Success).data
        assertThat(data.records).hasSize(1)
        assertThat(data.accountHash).isEqualTo(AccountHasher.hash("20260001"))
    }

    @Test fun `cross month teaching week days remain valid`() {
        val body = """{"data":{"week1":[{"date":"2026-09-30"},$record]}}"""
        assertThat((parse(body) as RemoteResult.Success).data.records).hasSize(2)
    }
}
