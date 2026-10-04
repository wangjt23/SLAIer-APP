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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume

/** Measured off-screen like WebViewExtractor; never attached to an Activity or opens a browser. */
class SilentSchoolLogin @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun signIn(
        entry: String,
        store: SavedLoginStore,
        authorizeCredentials: suspend () -> Boolean,
        onManualRequired: () -> Unit,
        confirmSession: suspend () -> Boolean
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (!SilentLoginUrls.allowed(entry)) return@withContext false
            var credentials: SchoolCredentials? = null
            val view = WebView(context)
            try {
                val page = LoginPage(view) { credentials }
                view.settings.configureForSchool()
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 1080, 1920)
                view.webViewClient = page.client
                view.loadUrl(entry)
                runSilentLogin(page, store::beginAttempt, confirmSession,
                    authorizeCredentials = {
                        // SSO redirects use only cookies. Decrypt the password only at an actual form.
                        if (!authorizeCredentials()) false else {
                            credentials = store.credentials()
                            credentials != null
                        }
                    }, onManualRequired = onManualRequired)
            } finally {
                // Cancellation (app backgrounded / manual login) also destroys the hidden page.
                view.stopLoading()
                view.webViewClient = WebViewClient()
                view.destroy()
            }
        }

    private class LoginPage(private val view: WebView, private val credentials: () -> SchoolCredentials?) : SilentLoginPage {
        override val url: String? get() = view.url
        override var failed = false
        override var loaded = false
        override var generation = 0

        val client = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                loaded = false
                generation++
                if (!SilentLoginUrls.allowed(url)) failed = true
            }

            override fun onPageFinished(view: WebView?, url: String?) { loaded = true }

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
