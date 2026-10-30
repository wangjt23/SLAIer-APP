package com.slai.campus.data.stu

import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.core.network.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.time.Clock

class StuAttendancePeriodDataSourceTest {
    private val loginForm = """<form id="loginForm" action="/a/login"><input name="username"><input type="password" name="password"></form>"""

    @Test fun `period request uses school month query and protected ajax headers`() {
        val request = attendancePeriodRequest("https://stu.slai.edu.cn", "2026-11")
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.encodedPath).isEqualTo("/a/edu/acm/swipe/getWeeksInMonth")
        assertThat(request.url.queryParameter("month")).isEqualTo("2026-11")
        assertThat(request.header("X-Requested-With")).isEqualTo("XMLHttpRequest")
        assertThat(request.header("Cache-Control")).contains("no-cache")
    }

    @Test fun `complete protected school week list is accepted`() = runTest {
        assertThat(fetch(200, """{"success":true,"data":[{"range":"2026-10-26,2026-11-01"}]}"""))
            .isInstanceOf(RemoteResult.Success::class.java)
    }

    @Test fun `ordinary forbidden server error and maintenance link cannot trigger password login`() = runTest {
        listOf(403 to "Access denied", 503 to loginForm).forEach { (code, body) ->
            assertThat(fetch(code, body)).isInstanceOf(RemoteResult.ServerError::class.java)
        }
        assertThat(fetch(200, """<html>维护中 <a href="/a/login">登录</a></html>"""))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `real login challenge and login redirect report an expired session`() = runTest {
        assertThat(fetch(200, loginForm)).isEqualTo(RemoteResult.SessionExpired)
        assertThat(fetch(403, loginForm)).isEqualTo(RemoteResult.SessionExpired)
        assertThat(fetch(401, "Unauthorized")).isEqualTo(RemoteResult.SessionExpired)
        assertThat(fetch(302, "", "/a/login")).isEqualTo(RemoteResult.SessionExpired)
    }

    private suspend fun fetch(code: Int, body: String, location: String? = null) = StuAttendanceDataSource(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("Synthetic response")
                .apply { if (location != null) header("Location", location) }
                .body(body.toResponseBody()).build()
        }.build(),
        NetworkMonitor(ContextWrapper(null)), TimeProvider(Clock.systemUTC()), Dispatchers.Unconfined
    ).fetchPeriod("https://stu.slai.edu.cn", "2026-11")
}
