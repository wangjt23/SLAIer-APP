package com.slai.campus.core.network

import android.os.Handler
import android.os.Looper
import com.slai.campus.core.common.AppLog
import com.slai.campus.core.session.WebCookieBridge
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the WebView session into OkHttp, per URL.
 *
 * `CookieManager` is not thread safe and must be touched on the main thread, while OkHttp
 * interceptors run on background threads, so the bridge hop is explicit. The hop is cheap
 * (a string read) and is the price of not maintaining a second, divergent cookie jar.
 *
 * `Set-Cookie` values seen by OkHttp are written back into the WebView store so that a session
 * refreshed natively is also fresh for any page the user opens afterwards.
 */
@Singleton
class WebViewCookieInterceptor @Inject constructor(
    private val bridge: WebCookieBridge
) : Interceptor {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url.toString()

        val cookie = onMainThread { bridge.cookieHeader(url) }
        val request = if (cookie.isNullOrBlank()) {
            original
        } else {
            original.newBuilder()
                .header("Cookie", cookie)
                // Some school gateways serve different content to unknown agents; keep it stable.
                .header("Referer", original.url.newBuilder().encodedPath("/").build().toString())
                .build()
        }

        val response = chain.proceed(request)

        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            onMainThread { bridge.saveSetCookie(url, setCookies) }
        }
        return response
    }

    private fun <T> onMainThread(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).onFailure { AppLog.w("cookie bridge failed: ${it.javaClass.simpleName}") }.getOrNull()
        }
        var result: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            result = runCatching(block)
                .onFailure { AppLog.w("cookie bridge failed: ${it.javaClass.simpleName}") }
                .getOrNull()
            latch.countDown()
        }
        // Never block a network thread forever if the main thread is wedged.
        if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
            AppLog.w("cookie bridge timed out")
        }
        return result
    }
}
