package com.slai.campus.data.stu

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.session.AccountHasher
import org.junit.Test

class StuAccountIdentityTest {
    private fun body(a: String, b: String = a) = """{"success":true,"data":{"week1":[{"date":"2026-10-01","userNo":"$a"},{"date":"2026-10-02","userNo":"$b"}]},"stats":{}}"""

    @Test fun `protected records identify the owner without retaining raw student id`() {
        val result = StuAttendanceParser.parse(body("test-student-A"), "2026-10") as RemoteResult.Success
        assertThat(result.data.accountHash).isEqualTo(AccountHasher.hash("test-student-A"))
        assertThat(result.data.toString()).doesNotContain("test-student-A")
        assertThat(attendanceSessionCheck(200, null, body("test-student-A"), "2026-10").accountHash)
            .isEqualTo(result.data.accountHash)
    }

    @Test fun `mixed account records cannot enter one owner partition`() {
        assertThat(StuAttendanceParser.parse(body("test-student-A", "test-student-B"), "2026-10"))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `masked capture and genuinely empty response do not invent an owner`() {
        val masked = StuAttendanceParser.parse(body("***"), "2026-10") as RemoteResult.Success
        assertThat(masked.data.accountHash).isNull()
        val empty = StuAttendanceParser.parse("""{"success":true,"data":{},"weeks":[]}""", "2026-10") as RemoteResult.Success
        assertThat(empty.data.accountHash).isNull()
    }
}
