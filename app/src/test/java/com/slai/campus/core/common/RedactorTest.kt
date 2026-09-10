package com.slai.campus.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Redaction is a security control, not formatting: these strings end up in logcat, in the diagnostics
 * console and in screenshots a user may share.
 */
class RedactorTest {

    @Test
    fun `redacts cookie header values`() {
        val out = Redactor.redact("Cookie: JSESSIONID=ABCDEF123456; jeesite.session.id=deadbeef1234")
        assertThat(out).doesNotContain("ABCDEF123456")
        assertThat(out).doesNotContain("deadbeef1234")
    }

    @Test
    fun `redacts set-cookie`() {
        val out = Redactor.redact("Set-Cookie: JSESSIONID=271E0523F9A83C24B7EF9D73E34B8F1D; Path=/yjsxt")
        assertThat(out).doesNotContain("271E0523F9A83C24B7EF9D73E34B8F1D")
    }

    @Test
    fun `redacts authorization and tokens`() {
        val out = Redactor.redact("""{"access_token":"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9","password":"hunter2"}""")
        assertThat(out).doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")
        assertThat(out).doesNotContain("hunter2")
    }

    @Test
    fun `redacts student identifiers`() {
        val out = Redactor.redact("xh=20260001 yhm=zhangsan")
        assertThat(out).doesNotContain("20260001")
        assertThat(out).doesNotContain("zhangsan")
    }

    @Test
    fun `url redaction keeps host and path but masks query secrets`() {
        val out = Redactor.redactUrl("https://sis.slai.edu.cn/yjsxt/htxylogin?code=SECRET123&state=441811")
        assertThat(out).contains("sis.slai.edu.cn")
        assertThat(out).contains("/yjsxt/htxylogin")
        assertThat(out).doesNotContain("SECRET123")
        // state is not a secret and is useful for correlating requests
        assertThat(out).contains("state=441811")
    }

    @Test
    fun `header map redaction masks whole values`() {
        val redacted = Redactor.redactHeaders(
            mapOf("Cookie" to listOf("a=b"), "Accept" to listOf("application/json"))
        )
        assertThat(redacted["Cookie"]).containsExactly("***")
        assertThat(redacted["Accept"]).containsExactly("application/json")
    }

    @Test
    fun `preview truncates and redacts`() {
        val body = """{"kbList":[],"cookie":"JSESSIONID=AAAABBBBCCCCDDDD1234"}"""
        val preview = Redactor.preview(body, maxChars = 20)
        assertThat(preview.length).isAtMost(21)
        assertThat(preview).doesNotContain("AAAABBBBCCCCDDDD1234")
    }

    @Test
    fun `plain text is untouched`() {
        assertThat(Redactor.redact("正在同步课表")).isEqualTo("正在同步课表")
    }
}
