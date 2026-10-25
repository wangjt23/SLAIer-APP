package com.slai.campus.data.stu

import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.network.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.time.LocalDate

/** Exercise the real HTTP response path, rather than only the login-markup helper. */
class StuPunchDataSourceTest {
    private val day = LocalDate.of(2026, 10, 24)
    private val loginForm = """<html><form id="loginForm" action="/a/login">
        <input name="username"><input type="password" name="password"></form></html>"""
    private val validPunches = """{"code":0,"count":1,"data":[{
        "id":"synthetic-event","swipeTime":"2026-10-24 08:00:00","eventType":"进门"
    }]}"""

    @Test fun `ordinary forbidden is server error and does not request login`() = runTest {
        val result = fetch(403, "Access denied by gateway")
        assertThat(result).isInstanceOf(RemoteResult.ServerError::class.java)
        assertThat((result as RemoteResult.ServerError).code).isEqualTo(403)
    }

    @Test fun `forbidden with login navigation link is still server error`() = runTest {
        val result = fetch(403, """<html>Access denied <a href="/a/login">登录</a></html>""")
        assertThat(result).isInstanceOf(RemoteResult.ServerError::class.java)
    }

    @Test fun `forbidden containing actual login form is expired`() = runTest {
        assertThat(fetch(403, loginForm)).isEqualTo(RemoteResult.SessionExpired)
    }

    @Test fun `unauthorized is expired without form markup`() = runTest {
        assertThat(fetch(401, "Unauthorized")).isEqualTo(RemoteResult.SessionExpired)
    }

    @Test fun `redirect to school login is expired`() = runTest {
        assertThat(fetch(302, "", "/a/login?redirect=attendance"))
            .isEqualTo(RemoteResult.SessionExpired)
    }

    @Test fun `unrelated redirect does not request login`() = runTest {
        assertThat(fetch(302, "", "/maintenance"))
            .isInstanceOf(RemoteResult.ServerError::class.java)
    }

    @Test fun `successful response containing actual login form is expired`() = runTest {
        assertThat(fetch(200, loginForm)).isEqualTo(RemoteResult.SessionExpired)
    }

    @Test fun `valid payload is accepted`() = runTest {
        val result = fetch(200, validPunches)
        assertThat(result).isInstanceOf(RemoteResult.Success::class.java)
        assertThat((result as RemoteResult.Success).data.single().id).isEqualTo("synthetic-event")
    }

    @Test fun `login URL inside valid JSON does not invalidate a session`() = runTest {
        val result = fetch(200, validPunches.replace("\"code\":0", "\"code\":0,\"helpUrl\":\"/a/login\""))
        assertThat(result).isInstanceOf(RemoteResult.Success::class.java)
    }

    @Test fun `HTML error with a login link is schema error not session expiry`() = runTest {
        assertThat(fetch(200, """<html>系统维护 <a href="/a/login">登录</a></html>"""))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `invalid success JSON remains schema error`() = runTest {
        assertThat(fetch(200, """{"code":0,"data":null}"""))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `server failure with login form remains a server failure`() = runTest {
        assertThat(fetch(503, loginForm)).isInstanceOf(RemoteResult.ServerError::class.java)
    }

    private suspend fun fetch(code: Int, body: String, location: String? = null) =
        StuPunchDataSource(
            OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 403) "Forbidden" else "Synthetic response")
                    .apply { if (location != null) header("Location", location) }
                    .body(body.toResponseBody("text/plain".toMediaType()))
                    .build()
            }.build(),
            // JVM Android stubs return no ConnectivityManager, so only the synthetic interceptor runs.
            NetworkMonitor(ContextWrapper(null)),
            Dispatchers.Unconfined
        ).fetchPunches("https://stu.slai.edu.cn", day, day).result
}
