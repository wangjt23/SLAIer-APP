package com.slai.campus.data.stu

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import org.junit.Test

/**
 * The safety property under test: an unreadable response must yield UNKNOWN, never NOT_CHECKED_IN.
 */
class StuCheckInParserTest {

    private fun answer(body: String, contentType: String = "application/json"): StuCheckInAnswer {
        val result = StuCheckInParser.classify(body, contentType)
        assertThat(result).isInstanceOf(RemoteResult.Success::class.java)
        return (result as RemoteResult.Success).data
    }

    @Test
    fun `boolean checked field`() {
        assertThat(answer("""{"checked":true}""")).isEqualTo(StuCheckInAnswer.CHECKED_IN)
        assertThat(answer("""{"checked":false}""")).isEqualTo(StuCheckInAnswer.NOT_CHECKED_IN)
    }

    @Test
    fun `nested boolean field`() {
        assertThat(answer("""{"code":0,"data":{"signed":true}}"""))
            .isEqualTo(StuCheckInAnswer.CHECKED_IN)
    }

    @Test
    fun `numeric status field`() {
        assertThat(answer("""{"status":1}""")).isEqualTo(StuCheckInAnswer.CHECKED_IN)
        assertThat(answer("""{"status":0}""")).isEqualTo(StuCheckInAnswer.NOT_CHECKED_IN)
    }

    @Test
    fun `chinese markers in html`() {
        assertThat(answer("<html><body>今日打卡：已打卡</body></html>", "text/html"))
            .isEqualTo(StuCheckInAnswer.CHECKED_IN)
        assertThat(answer("<html><body>状态：未打卡</body></html>", "text/html"))
            .isEqualTo(StuCheckInAnswer.NOT_CHECKED_IN)
    }

    @Test
    fun `ambiguous page yields unknown`() {
        // A legend listing both states must not be read as an answer.
        assertThat(answer("<html>已打卡 / 未打卡</html>", "text/html"))
            .isEqualTo(StuCheckInAnswer.UNKNOWN)
    }

    @Test
    fun `unrecognised payload yields unknown`() {
        assertThat(answer("""{"foo":"bar"}""")).isEqualTo(StuCheckInAnswer.UNKNOWN)
        assertThat(answer("<html><body>欢迎</body></html>", "text/html"))
            .isEqualTo(StuCheckInAnswer.UNKNOWN)
    }

    @Test
    fun `empty body is a schema change`() {
        assertThat(StuCheckInParser.classify("", "application/json"))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test
    fun `login page detection`() {
        assertThat(StuCheckInParser.looksLikeLoginPage("<html>/a/login</html>")).isTrue()
        assertThat(StuCheckInParser.isLoginUrl("https://stu.slai.edu.cn/a/login;JSESSIONID=abc")).isTrue()
        assertThat(StuCheckInParser.isLoginUrl("https://stu.slai.edu.cn/a/index")).isFalse()
    }
}
