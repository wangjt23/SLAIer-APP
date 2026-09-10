package com.slai.campus.core.session

import android.webkit.CookieManager
import com.slai.campus.core.common.AppLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The session bridge: WebView is the source of truth for cookies, OkHttp borrows them per URL.
 *
 * Why per URL and not a global cookie jar: the school runs two independent OAuth clients on one
 * AD FS. `sis.slai.edu.cn` and `stu.slai.edu.cn` have different cookie names, paths and lifetimes.
 * Handing every `*.slai.edu.cn` request the union of all cookies would leak one system's session to
 * the other and would break as soon as either system adds a path-scoped cookie.
 *
 * `CookieManager.getCookie(url)` is documented to return the cookies *applicable to that URL*, which
 * is exactly the semantics we need. Every call must happen on the main thread.
 */
@Singleton
class WebCookieBridge @Inject constructor() {

    private val cookieManager: CookieManager
        get() = CookieManager.getInstance()

    /** Cookies that would be sent to [url], or null when there are none. Main thread only. */
    fun cookieHeader(url: String): String? =
        runCatching { cookieManager.getCookie(url) }
            .onFailure { AppLog.w("getCookie failed: ${it.javaClass.simpleName}") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** Stores `Set-Cookie` values that OkHttp saw, so the WebView stays in sync. */
    fun saveSetCookie(url: String, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        runCatching {
            setCookieHeaders.forEach { raw -> cookieManager.setCookie(url, raw) }
            cookieManager.flush()
        }.onFailure { AppLog.w("setCookie failed: ${it.javaClass.simpleName}") }
    }

    /** True when any cookie exists for [url]. Used as a cheap "did login leave anything behind". */
    fun hasCookies(url: String): Boolean = !cookieHeader(url).isNullOrBlank()

    /**
     * Names and value *lengths* of the cookies that would be sent to [url] — never the values.
     *
     * This is the single most useful line in a diagnostic report: every "the timetable never loads"
     * case so far has come down to this header being empty (the server answers 901 with a 0-byte body
     * when a request arrives without a valid session).
     */
    fun cookieSummary(url: String): String {
        val header = cookieHeader(url) ?: return "无 Cookie → 未登录（服务端会返回 901）"
        val parts = header.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "无 Cookie → 未登录（服务端会返回 901）"
        return parts.joinToString(", ") { part ->
            val name = part.substringBefore('=').trim()
            val length = part.substringAfter('=', "").length
            "$name=${length}字节"
        }
    }

    /**
     * Persists the cookie store.
     *
     * Android keeps cookies in memory and only writes them out on flush (or on a clean shutdown), so
     * without this a session established in the login WebView can be lost when the app is killed —
     * which then looks exactly like "the timetable stopped working".
     */
    fun flush() {
        runCatching { cookieManager.flush() }
            .onFailure { AppLog.w("cookie flush failed: ${it.javaClass.simpleName}") }
    }

    // ---------------------------------------------------------------------------------------
    // Background-thread access
    // ---------------------------------------------------------------------------------------

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * [cookieHeader] for callers that are not on the main thread.
     *
     * `CookieManager` is documented as main-thread-only, and the SSO renewal below is a blocking
     * network loop, so the hop has to be explicit. A 2 s ceiling keeps a wedged main thread from
     * turning into a hung refresh; the caller simply sees "no cookies" and reports a login problem.
     */
    fun cookieHeaderBlocking(url: String): String? = onMain { cookieHeader(url) }

    /** [saveSetCookie] for callers that are not on the main thread. */
    fun saveSetCookieBlocking(url: String, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        onMain { saveSetCookie(url, setCookieHeaders) }
    }

    private fun <T> onMain(block: () -> T): T? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return runCatching(block)
                .onFailure { AppLog.w("cookie bridge failed: ${it.javaClass.simpleName}") }
                .getOrNull()
        }
        var result: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            result = runCatching(block)
                .onFailure { AppLog.w("cookie bridge failed: ${it.javaClass.simpleName}") }
                .getOrNull()
            latch.countDown()
        }
        if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
            AppLog.w("cookie bridge timed out")
        }
        return result
    }

    /**
     * Removes cookies for one host. Called when the user explicitly signs out; the app never does
     * this automatically, because silently dropping a valid session would be user-hostile.
     */
    fun clearHostCookies(host: String) {
        val manager = cookieManager
        runCatching {
            val cookies = manager.getCookie("https://$host") ?: return
            cookies.split(';').map { it.substringBefore('=').trim() }
                .filter { it.isNotEmpty() }
                .forEach { name ->
                    // Expire each cookie on the exact paths the school uses.
                    listOf("/", "/yjsxt", "/sso", "/adfs").forEach { path ->
                        manager.setCookie("https://$host", "$name=; Max-Age=0; Path=$path")
                    }
                }
            manager.flush()
        }.onFailure { AppLog.w("clearHostCookies failed: ${it.javaClass.simpleName}") }
    }

    fun clearAll() {
        runCatching {
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        }.onFailure { AppLog.w("clearAll failed: ${it.javaClass.simpleName}") }
    }
}
