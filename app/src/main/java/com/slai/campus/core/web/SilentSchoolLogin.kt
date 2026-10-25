package com.slai.campus.core.web

import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.slai.campus.core.session.SavedLoginStore
import com.slai.campus.core.session.SchoolCredentials
import com.slai.campus.core.session.WebCookieBridge
import com.slai.campus.core.common.SchoolSystem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume

/** Measured off-screen like WebViewExtractor; never attached to an Activity or opens a browser. */
class SilentSchoolLogin @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cookieBridge: WebCookieBridge
) {
    suspend fun signIn(
        entry: String,
        store: SavedLoginStore,
        authorizeCredentials: suspend () -> Boolean,
        onManualRequired: () -> Unit,
        confirmSession: suspend () -> Boolean,
        expectedRevision: Long = store.status.value.revision
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (!SilentLoginUrls.allowed(entry)) return@withContext false
            var credentials: SchoolCredentials? = null
            val view = WebView(context)
            val owner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            var destroyed = false
            fun destroy() {
                if (destroyed) return
                destroyed = true
                runCatching { view.stopLoading() }
                runCatching { view.webViewClient = WebViewClient() }
                runCatching { view.destroy() }
            }
            val cookieNavigation = cookieBridge.beginBrowserNavigation(SchoolSystem.STU.host) {
                destroy()
                owner?.cancel(kotlinx.coroutines.CancellationException("school session cleared"))
            }
            try {
                val page = LoginPage(view, { credentials }) {
                    val status = store.status.value
                    status.saved && status.enabled && !status.paused && status.revision == expectedRevision
                }
                view.settings.configureForSchool()
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 1080, 1920)
                view.webViewClient = page.client
                view.loadUrl(entry)
                runSilentLogin(page, { store.beginAttempt(expectedRevision) }, confirmSession,
                    authorizeCredentials = {
                        // SSO redirects use only cookies. Decrypt the password only at an actual form.
                        if (!authorizeCredentials()) false else {
                            credentials = store.credentials(expectedRevision)
                            credentials != null
                        }
                    }, onManualRequired = onManualRequired)
            } finally {
                // Cancellation (app backgrounded / manual login) also destroys the hidden page.
                try {
                    destroy()
                } finally { cookieNavigation.close() }
            }
        }

    private class LoginPage(
        private val view: WebView,
        private val credentials: () -> SchoolCredentials?,
        private val canSubmit: () -> Boolean
    ) : SilentLoginPage {
        override val url: String? get() = view.url
        override var failed = false
        override var generation = 0

        val client = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                generation++
                if (!SilentLoginUrls.allowed(url)) failed = true
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                rejectNavigation(request?.url?.toString())

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = rejectNavigation(url)

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) failed = true
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                if (request?.isForMainFrame == true) failed = true
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.cancel()
                failed = true
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                failed = true
                return true
            }
        }

        private fun rejectNavigation(url: String?): Boolean {
            val rejected = !SilentLoginUrls.allowed(url)
            if (rejected) failed = true
            return rejected
        }

        override suspend fun inspect(): String = evaluate(SchoolLoginScript.inspect())
        override suspend fun submit(password: Boolean): String {
            if (!canSubmit()) return "refused"
            val saved = credentials() ?: return "refused"
            return evaluate(SchoolLoginScript.submit(saved, password))
        }

        private suspend fun evaluate(script: String): String = suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result?.trim('"').orEmpty())
            }
        }
    }
}
