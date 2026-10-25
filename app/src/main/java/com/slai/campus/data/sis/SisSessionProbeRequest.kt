package com.slai.campus.data.sis

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.session.SessionState
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/** Cancellation releases the session-probe mutex even while a response body is still arriving. */
internal suspend fun probeSisCall(call: Call): SessionState? = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) {
                AppLog.w("SIS probe transport failure: ${e.javaClass.simpleName}")
                continuation.resume(SessionState.ERROR)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            val result = response.use { res ->
                val location = res.header("Location")
                when {
                    res.code in 300..399 && SisScheduleParser.isLoginUrl(location) -> SessionState.EXPIRED
                    res.code in 300..399 && location != null -> null
                    res.code == 200 -> {
                        val body = runCatching { res.peekBody(8 * 1024).string() }.getOrNull()
                        if (body.isNullOrBlank()) SessionState.ERROR
                        else if (SisScheduleParser.looksLikeLoginPage(body)) SessionState.EXPIRED
                        else SessionState.AUTHENTICATED
                    }
                    res.code in setOf(401, 403, 901) -> SessionState.EXPIRED
                    else -> null
                }
            }
            if (continuation.isActive) continuation.resume(result)
        }
    })
}
