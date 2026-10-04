package com.slai.campus.data.stu

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.session.SessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Response
import okio.Timeout
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StuAttendanceSessionVerifierTest {
    private val month = "2026-10"
    private fun state(code: Int, body: String?, location: String? = null) = attendanceSessionState(code, location, body, month)

    @Test fun `valid attendance JSON wins over incidental login words`() {
        val json = """{"weeks":[],"data":{},"stats":{"link":"/a/login","label":"JeeSite login"},"success":true}"""
        assertThat(state(200, json)).isEqualTo(SessionState.AUTHENTICATED)
    }

    @Test fun `login navigation and assets in ordinary HTML do not imply expiration`() {
        val html = """<html><script src="jeesite-login.js"></script><a href="/a/login">Sign in</a></html>"""
        assertThat(StuCheckInParser.hasLoginForm(html)).isFalse()
        assertThat(state(200, html)).isEqualTo(SessionState.ERROR)
    }

    @Test fun `bare permission errors and service errors do not start password login`() {
        for (code in listOf(403, 404, 429, 500, 503)) {
            assertThat(state(code, "Access denied")).isEqualTo(SessionState.ERROR)
        }
    }

    @Test fun `real login forms and redirects confirm expiration`() {
        for (html in listOf("<form id='loginFormPaginated'></form>",
            "<form method='post' action='/a/login'><input name='username'></form>")) {
            assertThat(state(200, html)).isEqualTo(SessionState.EXPIRED)
            assertThat(state(403, html)).isEqualTo(SessionState.EXPIRED)
        }
        assertThat(state(302, "", "/sso/login")).isEqualTo(SessionState.EXPIRED)
        assertThat(state(401, "")).isEqualTo(SessionState.EXPIRED)
        assertThat(state(302, "", "/help")).isEqualTo(SessionState.ERROR)
    }

    @Test fun `empty and malformed data are not authentication evidence`() {
        for (body in listOf(null, "", "{}", "not json", """{"success":false,"message":"Error"}""")) {
            assertThat(state(200, body)).isEqualTo(SessionState.ERROR)
        }
    }

    @Test fun `verification uses the exact attendance request and forbids cached responses`() {
        val request = attendanceRequest("https://stu.slai.edu.cn", month)
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.toString()).isEqualTo("https://stu.slai.edu.cn/a/edu/acm/swipe/weekGroupedByMonth")
        assertThat(request.header("Cache-Control")).isEqualTo("no-cache, no-store")
        val form = request.body as FormBody
        assertThat(form.value(0)).isEqualTo(month)
        assertThat(form.name(0)).isEqualTo("startMonth")
    }

    @Test fun `cancelled verification cancels its HTTP request`() = runTest {
        var cancelled = false
        val call = object : Call {
            private val timer = Timeout()
            override fun request() = attendanceRequest("https://stu.slai.edu.cn", month)
            override fun execute(): Response = error("must not block")
            override fun enqueue(responseCallback: Callback) {}
            override fun cancel() { cancelled = true }
            override fun isCanceled() = cancelled
            override fun isExecuted() = true
            override fun timeout() = timer
            override fun clone(): Call = error("unused")
        }
        assertThat(withTimeoutOrNull(100) { checkAttendanceSessionCall(call, month) }).isNull()
        assertThat(cancelled).isTrue()
    }
}
