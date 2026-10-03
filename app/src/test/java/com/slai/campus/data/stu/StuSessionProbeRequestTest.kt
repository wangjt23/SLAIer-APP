package com.slai.campus.data.stu

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.session.SessionState
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StuSessionProbeRequestTest {
    private class PendingCall(val body: String? = null) : Call {
        var cancelled = false
        private val timeout = Timeout()
        override fun request() = Request.Builder().url("https://stu.slai.edu.cn/a/sys/user/info").build()
        override fun execute(): Response = error("blocking execute must not be used")
        override fun enqueue(responseCallback: Callback) {
            if (body != null) responseCallback.onResponse(this, Response.Builder().request(request())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build())
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = true
        override fun isCanceled() = cancelled
        override fun timeout() = timeout
        override fun clone(): Call = PendingCall(body)
    }

    @Test fun `deadline cancels the HTTP call instead of waiting for socket timeout`() = runTest {
        val call = PendingCall()
        assertThat(withTimeoutOrNull(100) { probeStuCall(call) }).isNull()
        assertThat(call.cancelled).isTrue()
        assertThat(testScheduler.currentTime).isEqualTo(100)
    }

    @Test fun `empty response is not proof of authentication`() = runTest {
        assertThat(probeStuCall(PendingCall(""))).isEqualTo(SessionState.ERROR)
        assertThat(probeStuCall(PendingCall("<form action='/a/login'>JeeSite login</form>")))
            .isEqualTo(SessionState.EXPIRED)
        assertThat(probeStuCall(PendingCall("<html>protected user info</html>")))
            .isEqualTo(SessionState.AUTHENTICATED)
    }
}
