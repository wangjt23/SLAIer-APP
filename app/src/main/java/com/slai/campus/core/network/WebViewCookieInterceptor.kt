package com.slai.campus.core.network

import com.slai.campus.core.session.WebCookieBridge
import com.slai.campus.core.session.CookieRequestSnapshot
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    /** Runs once outside OkHttp's retry/redirect loop; every wire attempt retains this permit. */
    val requestGuard: Interceptor = Interceptor { chain ->
        guardWebViewCookieRequest(chain, bridge::requestSnapshotBlocking)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        return bridgeWebViewCookies(chain,
            readCookie = bridge::requestSnapshotBlocking,
            saveCookies = bridge::saveIfUnchangedBlocking)
    }
}

/** Session probes borrow cookies but must never replace a concurrent browser login's session. */
internal object ReadOnlySessionCheck

/** Request-scoped SSO ownership, rechecked at the actual main-thread Cookie commit. */
class CookieWritePermit(val canWrite: () -> Boolean)

internal fun guardWebViewCookieRequest(
    chain: Interceptor.Chain,
    readCookie: (String) -> CookieRequestSnapshot?
): Response {
    val original = chain.request()
    val snapshot = readCookie(original.url.toString())
    val previous = original.tag(CookieWritePermit::class.java)
    val permit = CookieWritePermit { snapshot?.writePermitted() == true && previous?.canWrite() != false }
    return chain.proceed(original.newBuilder().tag(CookieWritePermit::class.java, permit).build())
}

internal fun bridgeWebViewCookies(
    chain: Interceptor.Chain,
    readCookie: (String) -> CookieRequestSnapshot?,
    saveCookies: (String, CookieRequestSnapshot?, List<String>) -> Unit
): Response {
    val original = chain.request()
    val url = original.url.toString()

    val permit = original.tag(CookieWritePermit::class.java)
    val snapshot = readCookie(url)?.let { if (permit == null) it else it.withWritePermit(permit.canWrite) }
    val cookie = snapshot?.header
    val referer = original.header("Referer")?.toHttpUrlOrNull()?.takeIf {
        it.scheme == original.url.scheme && it.host == original.url.host && it.port == original.url.port
    }?.newBuilder()?.query(null)?.fragment(null)?.username("")?.password("")?.build()
    val request = original.newBuilder().apply {
        // Redirects inherit Response.request's tags. Preserve the first hop's browser/clear
        // generation throughout the call, while each hop still uses fresh host/path cookies.
        // A normal Set-Cookie write does not change this global generation.
        if (snapshot != null) tag(CookieWritePermit::class.java, CookieWritePermit(snapshot::writePermitted))
        // Redirects/retries must not carry a previous hop's Cookie header to a cookie-less host.
        if (cookie.isNullOrBlank()) removeHeader("Cookie") else header("Cookie", cookie)
        when {
            referer != null -> header("Referer", referer.toString())
            !cookie.isNullOrBlank() -> header("Referer", original.url.newBuilder().encodedPath("/")
                .query(null).fragment(null).username("").password("").build().toString())
            else -> removeHeader("Referer")
        }
    }.build()

    val response = chain.proceed(request)

    val setCookies = response.headers("Set-Cookie")
    if (setCookies.isNotEmpty() && original.tag(ReadOnlySessionCheck::class.java) == null &&
        (permit == null || permit.canWrite())) {
        saveCookies(url, snapshot, setCookies)
    }
    return response
}
