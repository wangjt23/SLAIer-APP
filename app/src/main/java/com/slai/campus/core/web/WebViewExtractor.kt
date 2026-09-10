package com.slai.campus.core.web

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.slai.campus.BuildConfig
import com.slai.campus.core.common.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** A table scraped from the rendered DOM, as a grid of trimmed cell texts. */
data class DomTable(
    val id: String,
    val cssClass: String,
    val rows: List<List<String>>
)

/** Everything a headless page load produced. */
data class WebCapture(
    val requestedUrl: String,
    val finalUrl: String?,
    val title: String?,
    val bodyText: String?,
    val capturedBodies: List<CaptureRecord>,
    val tables: List<DomTable>,
    val error: String? = null
) {
    val hasJsonPayload: Boolean
        get() = capturedBodies.any { it.body.trimStart().startsWith("{") || it.body.trimStart().startsWith("[") }
}

/**
 * Loads a school page in an off-screen WebView and reads the data out of it.
 *
 * This is the plan's `WEBVIEW_EXTRACT` mode, and it is the most robust read path available:
 *  - the page itself performs the request, so **no request parameters are guessed**;
 *  - the response is captured through a small hook installed into the page's own `XMLHttpRequest`
 *    and `fetch`, which is far less brittle than scraping rendered HTML;
 *  - a DOM scrape of the largest tables is kept as a second, independent fallback;
 *  - **no JavaScript bridge is exposed**. The hook writes into a page-global array and the native side
 *    reads it back with `evaluateJavascript`, so no page can call into the app.
 *
 * The WebView is never attached to the activity; it is measured and laid out manually so that
 * layout-dependent scripts still run.
 */
@Singleton
class WebViewExtractor @Inject constructor(
    private val context: Context
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun capture(
        url: String,
        userAgent: String? = null,
        waitAfterPageFinishedMs: Long = 3_000,
        timeoutMs: Long = 30_000
    ): WebCapture = withContext(Dispatchers.Main.immediate) {
        require(AllowedHosts.isAllowed(url)) { "refusing to load a non-school URL: ${AllowedHosts.hostOf(url)}" }

        val finished = CompletableDeferred<Unit>()
        var finalUrl: String? = null
        var pageTitle: String? = null
        var pageError: String? = null

        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(PAGE_WIDTH, PAGE_HEIGHT)
            // Give the renderer a real viewport without attaching to the window.
            measure(
                View.MeasureSpec.makeMeasureSpec(PAGE_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(PAGE_HEIGHT, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, PAGE_WIDTH, PAGE_HEIGHT)
            settings.configureForSchool()
            userAgent?.let { settings.userAgentString = it }
            // The AD FS flow is a cross-host redirect chain, so third-party cookies must be allowed
            // for this WebView (it is never used to browse anything outside the allow-list).
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = null
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // Install the hook as early as possible, then again after load in case the page
                    // navigated client-side and lost it.
                    view.evaluateJavascript(CaptureScript.INSTALL, null)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    finalUrl = url
                    view.evaluateJavascript(CaptureScript.INSTALL, null)
                    if (!finished.isCompleted) finished.complete(Unit)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    // Only main-frame failures matter; sub-resource failures are common and harmless.
                    if (request?.isForMainFrame == true) {
                        pageError = "onReceivedError(${error?.errorCode}): ${error?.description}"
                        if (!finished.isCompleted) finished.complete(Unit)
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    // Never proceed: an invalid certificate means the connection is not trustworthy.
                    AppLog.e("SSL error refused: ${error?.primaryError}")
                    handler?.cancel()
                    pageError = "TLS 证书校验失败"
                    if (!finished.isCompleted) finished.complete(Unit)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val target = request?.url?.toString() ?: return false
                    // Stay inside the school; anything else is not loaded here.
                    return !AllowedHosts.isAllowed(target)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? = null

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    pageError = "渲染进程崩溃 (didCrash=${detail?.didCrash()})"
                    if (!finished.isCompleted) finished.complete(Unit)
                    return true
                }
            }
            loadUrl(url)
        }

        try {
            runCatching { withTimeout(timeoutMs) { finished.await() } }
                .onFailure { if (it !is TimeoutCancellationException) throw it }
            if (!finished.isCompleted) pageError = pageError ?: "页面加载超时"

            // Let the page's own XHR settle after load.
            delay(waitAfterPageFinishedMs)

            val captured = readCapturedBodies(webView)
            val dom = readDom(webView)
            pageTitle = dom?.title
            finalUrl = finalUrl ?: dom?.url

            WebCapture(
                requestedUrl = url,
                finalUrl = finalUrl,
                title = pageTitle,
                bodyText = dom?.bodyText,
                capturedBodies = captured,
                tables = dom?.tables.orEmpty(),
                error = pageError
            )
        } finally {
            runCatching {
                webView.stopLoading()
                webView.webViewClient = WebViewClient()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
    }

    /** Reads the array the injected hook accumulated. */
    private suspend fun readCapturedBodies(webView: WebView): List<CaptureRecord> =
        CaptureScript.parse(evaluate(webView, CaptureScript.READ))

    /** title, final url, body text, tables */
    private suspend fun readDom(webView: WebView): DomSnapshot? {
        val raw = evaluate(webView, DOM_SCRIPT) ?: return null
        val obj = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        val tables = (obj["tables"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val t = entry as? JsonObject ?: return@mapNotNull null
            val rows = (t["rows"] as? JsonArray).orEmpty().mapNotNull { row ->
                (row as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            }
            DomTable(
                id = (t["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                cssClass = (t["cls"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                rows = rows
            )
        }
        return DomSnapshot(
            (obj["title"] as? JsonPrimitive)?.contentOrNull,
            (obj["url"] as? JsonPrimitive)?.contentOrNull,
            (obj["bodyText"] as? JsonPrimitive)?.contentOrNull,
            tables
        )
    }

    private suspend fun evaluate(webView: WebView, script: String): String? {
        val deferred = CompletableDeferred<String?>()
        webView.evaluateJavascript(script) { value -> deferred.complete(value) }
        return runCatching { withTimeout(5_000) { deferred.await() } }.getOrNull()
            // evaluateJavascript returns a JSON-encoded string; unwrap it.
            ?.let { if (it == "null") null else runCatching { json.parseToJsonElement(it) }.getOrNull() }
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
    }

    private data class DomSnapshot(
        val title: String?,
        val url: String?,
        val bodyText: String?,
        val tables: List<DomTable>
    )

    companion object {
        private const val PAGE_WIDTH = 1080
        private const val PAGE_HEIGHT = 1920

        /**
         * Hooks `XMLHttpRequest` and `fetch` so that the *page's own* requests are visible to us.
         * Bodies are capped so a runaway page cannot exhaust memory.
         */
        private val DOM_SCRIPT = """
            (function(){
              try {
                var out = {
                  title: document.title || '',
                  url: location.href || '',
                  bodyText: (document.body ? (document.body.innerText || '') : '').slice(0, 20000),
                  tables: []
                };
                var tables = document.querySelectorAll('table');
                for (var i = 0; i < tables.length && i < 8; i++) {
                  var t = tables[i];
                  var rows = [];
                  var trs = t.querySelectorAll('tr');
                  for (var r = 0; r < trs.length && r < 80; r++) {
                    var cells = [];
                    var tds = trs[r].querySelectorAll('th,td');
                    for (var c = 0; c < tds.length && c < 30; c++) {
                      cells.push(((tds[c].innerText || '') + '').replace(/\s+/g, ' ').trim());
                    }
                    if (cells.length) rows.push(cells);
                  }
                  if (rows.length) out.tables.push({ id: t.id || '', cls: (t.className || '') + '', rows: rows });
                }
                return JSON.stringify(out);
              } catch (e) { return JSON.stringify({ title:'', url:'', bodyText:'', tables:[] }); }
            })();
        """.trimIndent()

    }
}

/**
 * The WebView hardening the design plan requires, in one place so both the visible container and the
 * headless extractor are configured identically.
 */
fun WebSettings.configureForSchool() {
    javaScriptEnabled = true
    domStorageEnabled = true
    databaseEnabled = false

    allowFileAccess = false
    allowContentAccess = false
    @Suppress("DEPRECATION")
    allowFileAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    allowUniversalAccessFromFileURLs = false

    setSupportMultipleWindows(false)
    setGeolocationEnabled(false)
    javaScriptCanOpenWindowsAutomatically = false

    cacheMode = WebSettings.LOAD_DEFAULT
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    mediaPlaybackRequiresUserGesture = true

    // Keeps the school's desktop-first pages usable in the app.
    loadWithOverviewMode = true
    useWideViewPort = true
    builtInZoomControls = true
    displayZoomControls = false

    WebView.setWebContentsDebuggingEnabled(BuildConfig.WEBVIEW_DEBUG_ENABLED)
    CookieManager.getInstance().setAcceptCookie(true)
}
