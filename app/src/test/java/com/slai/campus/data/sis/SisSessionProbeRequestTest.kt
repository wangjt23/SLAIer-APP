package com.slai.campus.data.sis

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.session.SessionManager
import com.slai.campus.core.session.SessionProbe
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.session.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException

class SisSessionProbeRequestTest {
    @get:Rule val directory = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @After fun finish() { scope.cancel() }

    private class ControlledCall(
        private val body: ResponseBody? = null,
        private val code: Int = 200,
        private val location: String? = null,
        private val executor: Executor? = null
    ) : Call {
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        private val cancelled = AtomicBoolean(false)
        private val timer = Timeout()
        override fun request() = Request.Builder().url("https://sis.slai.edu.cn/yjsxt/framework/main.jsp").build()
        override fun execute(): Response = error("The session probe must not use blocking execute")
        override fun enqueue(callback: Callback) {
            entered.complete(Unit)
            if (body == null) return
            val respond = Runnable {
                try {
                    callback.onResponse(this, Response.Builder().request(request()).protocol(Protocol.HTTP_1_1)
                        .code(code).message("test").apply { location?.let { header("Location", it) } }
                        .body(body).build())
                } finally { finished.complete(Unit) }
            }
            if (executor == null) respond.run() else executor.execute(respond)
        }
        override fun cancel() { cancelled.set(true) }
        override fun isCanceled() = cancelled.get()
        override fun isExecuted() = entered.isCompleted
        override fun timeout() = timer
        override fun clone(): Call = error("unused")
    }

    @Test fun `classification retains existing SIS probe behavior`() = runBlocking {
        for (quote in listOf("'", "\"", "")) {
            val login = "<form action='/yjsxt/xtgl/login_slogin.html'><input name=${quote}yhm${quote}><input type='password' name=${quote}mm${quote}></form>"
            assertThat(probeSisCall(ControlledCall(login.toResponseBody()))).isEqualTo(SessionState.EXPIRED)
        }
        val home = "<a href='/yjsxt/xtgl/login_slogin.html'>重新登录</a><form><input name='yhm'></form>"
        assertThat(probeSisCall(ControlledCall(home.toResponseBody()))).isEqualTo(SessionState.AUTHENTICATED)
        assertThat(probeSisCall(ControlledCall("protected".toResponseBody()))).isEqualTo(SessionState.AUTHENTICATED)
        for (code in listOf(401, 403, 901)) {
            assertThat(probeSisCall(ControlledCall("".toResponseBody(), code))).isEqualTo(SessionState.EXPIRED)
        }
        assertThat(probeSisCall(ControlledCall("".toResponseBody(), 302, "/yjsxt/xtgl/login_slogin.html")))
            .isEqualTo(SessionState.EXPIRED)
        assertThat(probeSisCall(ControlledCall("".toResponseBody(), 302, "/other"))).isNull()
        assertThat(probeSisCall(ControlledCall("".toResponseBody(), 500))).isNull()
    }

    @Test fun `request deadline cancels the call before any headers arrive`() = runBlocking {
        val call = ControlledCall()
        assertThat(withTimeoutOrNull(100) { probeSisCall(call) }).isNull()
        assertThat(call.isCanceled()).isTrue()
    }

    @Test fun `empty or unreadable successful HTTP body is not proof of authentication`() = runBlocking {
        assertThat(probeSisCall(ControlledCall("".toResponseBody()))).isEqualTo(SessionState.ERROR)
        assertThat(probeSisCall(ControlledCall("  \n".toResponseBody()))).isEqualTo(SessionState.ERROR)
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("synthetic body failure")
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = "text/html".toMediaType()
            override fun contentLength() = 100L
            override fun source() = source
        }
        assertThat(probeSisCall(ControlledCall(body))).isEqualTo(SessionState.ERROR)
    }

    @Test fun `cancelled old probe releases real manager mutex before its body returns`() = runBlocking {
        val bodyEntered = CompletableDeferred<Unit>()
        val releaseBody = CountDownLatch(1)
        val bodyClosed = AtomicBoolean(false)
        val reader = Executors.newSingleThreadExecutor()
        val slowBody = object : ResponseBody() {
            override fun contentType() = "text/html".toMediaType()
            override fun contentLength() = -1L
            private val buffered = object : Source {
                private var consumed = false
                override fun timeout() = Timeout.NONE
                override fun close() { bodyClosed.set(true) }
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (consumed) return -1L
                    bodyEntered.complete(Unit)
                    check(releaseBody.await(10, TimeUnit.SECONDS))
                    consumed = true
                    val body = "<form action='/yjsxt/xtgl/login_slogin.html'><input name='yhm'></form>"
                    sink.writeUtf8(body)
                    return body.toByteArray().size.toLong()
                }
            }.buffer()
            override fun source() = buffered
        }
        val oldCall = ControlledCall(slowBody, executor = reader)
        val newCall = ControlledCall("protected new session".toResponseBody())
        val requests = AtomicInteger()
        val probe = object : SessionProbe {
            override val system = SchoolSystem.SIS
            override suspend fun probe() = probeSisCall(if (requests.getAndIncrement() == 0) oldCall else newCall)
                ?: SessionState.ERROR
        }
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { directory.newFile("session.preferences_pb") }
        val manager = SessionManager(SessionStore(prefs), setOf(probe), scope)
        val oldProbe = async { manager.probe(SchoolSystem.SIS, requireFresh = true) }
        try {
            withTimeout(5_000) { bodyEntered.await() }
            withTimeout(1_000) { oldProbe.cancelAndJoin() }
            assertThat(oldCall.isCanceled()).isTrue()
            assertThat(oldCall.finished.isCompleted).isFalse()
            manager.beginLogin(SchoolSystem.SIS)
            assertThat(withTimeout(1_000) { manager.probe(SchoolSystem.SIS, requireFresh = true) })
                .isEqualTo(SessionState.AUTHENTICATED)
            assertThat(oldCall.finished.isCompleted).isFalse()
            releaseBody.countDown()
            withTimeout(5_000) { oldCall.finished.await() }
            assertThat(bodyClosed.get()).isTrue()
            assertThat(manager.current().sis).isEqualTo(SessionState.AUTHENTICATED)
            assertThat(manager.authenticationVersion(SchoolSystem.SIS)).isEqualTo(1)
        } finally {
            releaseBody.countDown()
            oldProbe.cancelAndJoin()
            reader.shutdownNow()
        }
    }
}
