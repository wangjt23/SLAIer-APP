package com.slai.campus.core.session

import android.webkit.CookieManager
import com.slai.campus.core.common.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    private val guard = CookieSessionGuard()
    private val observedUrls = mutableMapOf<String, LinkedHashSet<String>>()

    private val cookieManager: CookieManager
        get() = CookieManager.getInstance()

    /** Cookies that would be sent to [url], or null when there are none. Main thread only. */
    fun cookieHeader(url: String): String? {
        rememberUrl(url)
        return runCatching { cookieManager.getCookie(url) }
            .onFailure { AppLog.w("getCookie failed: ${it.javaClass.simpleName}") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** Guard the complete browser lifetime, including redirects, XHR and async Cookie updates. */
    fun beginBrowserNavigation(host: String? = null, onInvalidated: () -> Unit = {}): AutoCloseable {
        val browser = guard.beginBrowser(host, onInvalidated)
        return AutoCloseable { guard.endBrowser(browser) }
    }

    /** Main thread only: stop older views before a new explicit login can establish its cookies. */
    fun cancelBrowserNavigations(host: String) { guard.invalidate(host) }

    internal fun requestSnapshotBlocking(url: String): CookieRequestSnapshot? =
        onMain { url.toHttpUrlOrNull()?.let { guard.snapshot(it.host, cookieHeader(url)) } }

    internal fun saveIfUnchangedBlocking(url: String, snapshot: CookieRequestSnapshot?, headers: List<String>) {
        if (snapshot == null || headers.isEmpty()) return
        onMain {
            if (snapshot.host == url.toHttpUrlOrNull()?.host && guard.claimWrite(snapshot, cookieHeader(url))) {
                // Two-argument setCookie is synchronous in Android WebView. Using the callback
                // overload here would release the main-thread guard before the write completes.
                headers.forEach { cookieManager.setCookie(url, it) }
                cookieManager.flush()
            }
        }
    }

    private fun rememberUrl(url: String) {
        val parsed = url.toHttpUrlOrNull() ?: return
        // Store paths only, never login query strings, tokens or Cookie values.
        val urls = observedUrls.getOrPut(parsed.host) { linkedSetOf() }
        urls += "${parsed.scheme}://${parsed.host}${parsed.encodedPath}"
        if (urls.size > 256) urls.remove(urls.first())
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

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * [cookieHeader] for callers that are not on the main thread.
     *
     * `CookieManager` is documented as main-thread-only, and the SSO renewal below is a blocking
     * network loop, so the hop has to be explicit. A 2 s ceiling keeps a wedged main thread from
     * turning into a hung refresh; the caller simply sees "no cookies" and reports a login problem.
     */
    fun cookieHeaderBlocking(url: String): String? = onMain { cookieHeader(url) }

    private fun <T> onMain(block: () -> T): T? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return runCatching(block)
                .onFailure { AppLog.w("cookie bridge failed: ${it.javaClass.simpleName}") }
                .getOrNull()
        }
        val result = java.util.concurrent.atomic.AtomicReference<T?>(null)
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)
        val task = Runnable {
            try {
                if (!abandoned.get()) result.set(runCatching(block)
                    .onFailure { AppLog.w("cookie bridge failed: ${it.javaClass.simpleName}") }
                    .getOrNull())
            } finally { latch.countDown() }
        }
        if (!mainHandler.post(task)) return null
        try {
            if (latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) return result.get()
            AppLog.w("cookie bridge timed out")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            // A timed-out request must not enqueue a Cookie write that runs after a later login.
            abandoned.set(true)
            mainHandler.removeCallbacks(task)
        }
        return null
    }

    /**
     * Removes cookies for one host. Called when the user explicitly signs out; the app never does
     * this automatically, because silently dropping a valid session would be user-hostile.
     */
    suspend fun clearHostCookiesAndAwait(host: String) = withContext(Dispatchers.Main.immediate) {
        cancelBrowserNavigations(host)
        val paths = HostCookieCleanup.paths(observedUrls[host].orEmpty())
        val removals = HostCookieCleanup.removals(host, paths, ::cookieHeader)
        removals.forEach { cookieManager.setCookie(it.url, it.value) }
        cookieManager.flush()
    }
}
