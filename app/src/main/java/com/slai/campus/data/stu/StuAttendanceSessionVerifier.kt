package com.slai.campus.data.stu

import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.network.StuApiClient
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.session.SessionStore
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.common.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.resume

/** A read-only attendance query, parsed with the same schema as an actual refresh. */
class StuAttendanceSessionVerifier @Inject constructor(
    @StuApiClient private val client: OkHttpClient,
    private val store: SessionStore,
    private val time: TimeProvider
) {
    suspend fun check(): SessionState = withContext(Dispatchers.IO) {
        withTimeoutOrNull(8_000) {
            val base = StuConfig.baseUrlOrDefault(store.baseUrl(SchoolSystem.STU))
            val month = StuAttendanceDataSource.monthOf(time.today())
            checkAttendanceSessionCall(client.newCall(attendanceRequest(base, month)), month)
        } ?: SessionState.ERROR
    }
}

internal fun attendanceSessionState(code: Int, location: String?, body: String?, month: String): SessionState = when {
    code in 300..399 && StuCheckInParser.isLoginUrl(location) -> SessionState.EXPIRED
    code == 401 -> SessionState.EXPIRED
    code == 403 && StuCheckInParser.hasLoginForm(body) -> SessionState.EXPIRED
    // 403 can be a permission denial or a gateway restriction, not a missing login.
    code !in 200..299 -> SessionState.ERROR
    StuCheckInParser.hasLoginForm(body) -> SessionState.EXPIRED
    StuAttendanceParser.parse(body, month) is RemoteResult.Success -> SessionState.AUTHENTICATED
    else -> SessionState.ERROR
}

internal suspend fun checkAttendanceSessionCall(call: Call, month: String): SessionState = suspendCancellableCoroutine { c ->
    call.timeout().timeout(8, TimeUnit.SECONDS)
    c.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (c.isActive) c.resume(SessionState.ERROR)
        }
        override fun onResponse(call: Call, response: Response) {
            val result = response.use {
                val body = runCatching { it.peekBody(4L * 1024 * 1024).string() }.getOrNull()
                attendanceSessionState(it.code, it.header("Location"), body, month)
            }
            if (c.isActive) c.resume(result)
        }
    })
}
