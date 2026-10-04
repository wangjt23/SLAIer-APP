package com.slai.campus.data.stu

import com.slai.campus.core.session.SessionState
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Cancellable including response-body reads; no cached state is promoted to success. */
internal suspend fun probeStuCall(call: Call): SessionState? = suspendCancellableCoroutine { continuation ->
    call.timeout().timeout(5, TimeUnit.SECONDS)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resume(SessionState.ERROR)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = response.use { res ->
                when {
                    res.code in 300..399 && StuCheckInParser.isLoginUrl(res.header("Location")) -> SessionState.EXPIRED
                    res.code == 401 -> SessionState.EXPIRED
                    res.code == 403 -> SessionState.ERROR
                    res.code == 200 -> {
                        val body = runCatching { res.peekBody(8 * 1024).string() }.getOrNull()
                        when {
                            body.isNullOrBlank() -> SessionState.ERROR
                            StuCheckInParser.hasLoginForm(body) -> SessionState.EXPIRED
                            else -> SessionState.AUTHENTICATED
                        }
                    }
                    else -> null // Try the next protected endpoint.
                }
            }
            if (continuation.isActive) continuation.resume(result)
        }
    })
}
