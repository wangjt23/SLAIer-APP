package com.slai.campus.core.session

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fake Cookie values only, in the debug app's WebView store. Never requests a school server. */
@RunWith(AndroidJUnit4::class)
class WebCookieBridgeTest {
    @Test fun actualWebViewResponseCookieIsSavedWithoutLogout() = checkDelayedBrowserCookie(clear = false)

    @Test fun logoutDestroysTargetBrowserBeforeLateResponseCookieAndKeepsSiblingBrowser() =
        checkDelayedBrowserCookie(clear = true)

    private fun checkDelayedBrowserCookie(clear: Boolean) = runBlocking<Unit> {
        val id = "test${UUID.randomUUID().toString().replace("-", "")}"
        DelayedCookieHttpsServer(id).use { server ->
            val bridge = WebCookieBridge()
            val loaded = CompletableDeferred<Unit>()
            var destroyed = false
            var revoked = false
            var siblingRevoked = false
            var view: WebView? = null
            var target: AutoCloseable? = null
            var sibling: AutoCloseable? = null
            try {
                withContext(Dispatchers.Main) {
                    val manager = CookieManager.getInstance()
                    manager.setCookie(server.url, "$id=old; Path=/a; Secure; HttpOnly")
                    view = WebView(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).apply {
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?,
                                                            error: android.net.http.SslError?) {
                                // The fixture is the only permitted self-signed URL. Production refuses TLS errors.
                                if (error?.url == server.url) handler?.proceed() else handler?.cancel()
                            }
                            override fun onPageFinished(view: WebView?, url: String?) { loaded.complete(Unit) }
                        }
                    }
                    target = bridge.beginBrowserNavigation("localhost") {
                        // Assert the callback runs before removal, not after a late browser response.
                        assertTrue(manager.getCookie(server.url).orEmpty().contains("$id=old"))
                        revoked = true
                        view?.stopLoading()
                        view?.webViewClient = WebViewClient()
                        view?.destroy()
                        destroyed = true
                    }
                    sibling = bridge.beginBrowserNavigation("sis.slai.edu.cn") { siblingRevoked = true }
                    view?.loadUrl(server.url)
                }
                withTimeout(15_000) {
                    kotlinx.coroutines.selects.select<Unit> {
                        server.entered.onAwait { }
                        server.failure.onAwait { error -> throw AssertionError("Local TLS fixture failed before HTTP request", error) }
                    }
                }
                if (clear) withContext(Dispatchers.Main) {
                    bridge.clearHostCookiesAndAwait("localhost")
                    assertTrue(revoked)
                    assertTrue(destroyed)
                    assertFalse(siblingRevoked)
                    assertFalse(CookieManager.getInstance().getCookie(server.url).orEmpty().contains("$id="))
                }
                server.respond()
                withTimeout(10_000) { server.returned.await() }
                if (clear) {
                    // Let the real Chromium networking thread process the released response headers.
                    delay(1_000)
                    withContext(Dispatchers.Main) {
                        assertFalse(CookieManager.getInstance().getCookie(server.url).orEmpty().contains("$id="))
                    }
                } else {
                    assertNull(server.failure.await())
                    withTimeout(10_000) { loaded.await() }
                    withContext(Dispatchers.Main) {
                        assertTrue(CookieManager.getInstance().getCookie(server.url).orEmpty().contains("$id=late"))
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (!destroyed) { view?.stopLoading(); view?.destroy() }
                    target?.close()
                    sibling?.close()
                    CookieManager.getInstance().setCookie(server.url, "$id=; Max-Age=0; Path=/a; Secure; HttpOnly")
                }
            }
        }
    }

    @Test fun clearsPathDomainAndSecureCookiesWithoutClearingSiblingOrSharedSession() = runBlocking<Unit> {
        withContext(Dispatchers.Main) {
            val id = "test${UUID.randomUUID().toString().replace("-", "")}"
            val manager = CookieManager.getInstance()
            val bridge = WebCookieBridge()
            val seeds = listOf(
                "https://stu.slai.edu.cn/a/index" to "$id=one; Path=/a; Secure; HttpOnly",
                "https://stu.slai.edu.cn/a/edu/acm/swipe/listData" to "${id}domain=two; Domain=stu.slai.edu.cn; Path=/a/edu; Secure",
                "https://stu.slai.edu.cn/sso/login" to "${id}shared=three; Domain=slai.edu.cn; Path=/; Secure; HttpOnly",
                "https://stu.slai.edu.cn/" to "__Host-$id=four; Path=/; Secure",
                "https://stu.slai.edu.cn/a/index" to "__Secure-$id=five; Path=/a; Secure",
                "https://stu.slai.edu.cn/custom/session/api" to "${id}custom=six; Path=/custom/session; Secure",
                "https://sis.slai.edu.cn/yjsxt/" to "${id}sibling=seven; Path=/yjsxt; Secure",
                "https://unrelated.example.invalid/" to "${id}other=eight; Path=/; Secure"
            )
            try {
                seeds.forEach { (url, value) -> manager.setCookie(url, value) }
                assertTrue(manager.getCookie("https://stu.slai.edu.cn/a/index").orEmpty().contains(id))
                bridge.cookieHeader("https://stu.slai.edu.cn/custom/session/api")
                bridge.clearHostCookiesAndAwait("stu.slai.edu.cn")
                val removedNames = setOf(id, "${id}domain", "__Host-$id", "__Secure-$id", "${id}custom")
                listOf("/", "/a/index", "/a/edu/acm/swipe/listData", "/sso/login", "/custom/session/api").forEach {
                    val remaining = manager.getCookie("https://stu.slai.edu.cn$it").orEmpty()
                        .split(';').map { cookie -> cookie.substringBefore('=').trim() }
                    assertFalse(remaining.any(removedNames::contains))
                }
                assertTrue(manager.getCookie("https://sis.slai.edu.cn/yjsxt/").orEmpty().contains("${id}sibling="))
                listOf("https://stu.slai.edu.cn/a/index", "https://sis.slai.edu.cn/yjsxt/",
                    "https://sts.slai.edu.cn/adfs/ls").forEach { url ->
                    assertTrue(manager.getCookie(url).orEmpty().contains("${id}shared=three"))
                }
                assertTrue(manager.getCookie("https://unrelated.example.invalid/").orEmpty().contains("${id}other="))
            } finally {
                seeds.forEach { (url, value) -> manager.setCookie(url, value.substringBefore(';').substringBefore('=') +
                    "=; Max-Age=0; " + value.substringAfter(';')) }
                manager.flush()
            }
        }
    }

    @Test fun staleNativeResponseCannotOverwriteAsyncBrowserCookieButFreshRenewalCan() = runBlocking<Unit> {
        val bridge = WebCookieBridge()
        val id = "test${UUID.randomUUID().toString().replace("-", "")}"
        val url = "https://stu.slai.edu.cn/a/index"
        val manager = withContext(Dispatchers.Main) { CookieManager.getInstance() }
        try {
            withContext(Dispatchers.Main) { manager.setCookie(url, "$id=old; Path=/a; Secure") }
            val old = withContext(Dispatchers.IO) { bridge.requestSnapshotBlocking(url) }
            withContext(Dispatchers.Main) {
                val browser = bridge.beginBrowserNavigation()
                try {
                    val written = CompletableDeferred<Unit>()
                    manager.setCookie(url, "$id=authenticated; Path=/a; Secure") { written.complete(Unit) }
                    withTimeout(5_000) { written.await() }
                } finally { browser.close() }
            }
            withContext(Dispatchers.IO) { bridge.saveIfUnchangedBlocking(url, old, listOf("$id=anonymous; Path=/a; Secure")) }
            withContext(Dispatchers.Main) { assertTrue(manager.getCookie(url).orEmpty().contains("$id=authenticated")) }
            withContext(Dispatchers.IO) {
                val fresh = bridge.requestSnapshotBlocking(url)
                bridge.saveIfUnchangedBlocking(url, fresh, listOf("$id=renewed; Path=/a; Secure"))
            }
            withContext(Dispatchers.Main) { assertTrue(manager.getCookie(url).orEmpty().contains("$id=renewed")) }
        } finally { withContext(Dispatchers.Main) { manager.setCookie(url, "$id=; Max-Age=0; Path=/a; Secure"); manager.flush() } }
    }

    @Test fun timedOutMainThreadWriteIsRemovedInsteadOfRunningLater() = runBlocking<Unit> {
        val bridge = WebCookieBridge()
        val id = "test${UUID.randomUUID().toString().replace("-", "")}"
        val url = "https://stu.slai.edu.cn/a/index"
        val manager = withContext(Dispatchers.Main) { CookieManager.getInstance().also { it.setCookie(url, "$id=old; Path=/a; Secure") } }
        val snapshot = withContext(Dispatchers.IO) { bridge.requestSnapshotBlocking(url) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            entered.countDown()
            release.await(8, TimeUnit.SECONDS)
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withContext(Dispatchers.IO) { bridge.saveIfUnchangedBlocking(url, snapshot, listOf("$id=late; Path=/a; Secure")) }
        } finally { release.countDown() }
        try {
            withContext(Dispatchers.Main) { assertTrue(manager.getCookie(url).orEmpty().contains("$id=old")) }
        } finally { withContext(Dispatchers.Main) { manager.setCookie(url, "$id=; Max-Age=0; Path=/a; Secure"); manager.flush() } }
    }

    @Test fun bothSystemsCanRenewFromConcurrentSnapshots() = runBlocking<Unit> {
        val bridge = WebCookieBridge()
        val id = "test${UUID.randomUUID().toString().replace("-", "")}"
        val stu = "https://stu.slai.edu.cn/a/index"
        val sis = "https://sis.slai.edu.cn/yjsxt/"
        val manager = withContext(Dispatchers.Main) {
            CookieManager.getInstance().also {
                it.setCookie(stu, "${id}stu=old; Path=/a; Secure")
                it.setCookie(sis, "${id}sis=old; Path=/yjsxt; Secure")
            }
        }
        try {
            withContext(Dispatchers.IO) {
                val stuSnapshot = bridge.requestSnapshotBlocking(stu)
                val sisSnapshot = bridge.requestSnapshotBlocking(sis)
                val oldSis = bridge.requestSnapshotBlocking(sis)
                bridge.saveIfUnchangedBlocking(stu, stuSnapshot, listOf("${id}stu=renewed; Path=/a; Secure"))
                bridge.saveIfUnchangedBlocking(sis, sisSnapshot, listOf("${id}sis=renewed; Path=/yjsxt; Secure"))
                bridge.saveIfUnchangedBlocking(sis, oldSis, listOf("${id}sis=anonymous; Path=/yjsxt; Secure"))
            }
            withContext(Dispatchers.Main) {
                assertTrue(manager.getCookie(stu).orEmpty().contains("${id}stu=renewed"))
                assertTrue(manager.getCookie(sis).orEmpty().contains("${id}sis=renewed"))
            }
        } finally {
            withContext(Dispatchers.Main) {
                manager.setCookie(stu, "${id}stu=; Max-Age=0; Path=/a; Secure")
                manager.setCookie(sis, "${id}sis=; Max-Age=0; Path=/yjsxt; Secure")
                manager.flush()
            }
        }
    }
}
