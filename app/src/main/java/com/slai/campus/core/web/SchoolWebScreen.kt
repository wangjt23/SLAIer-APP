package com.slai.campus.core.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.slai.campus.R
import com.slai.campus.core.common.AppLog

/**
 * The WebView container.
 *
 * Scope, per the design plan: this is the **authentication and web-fallback layer**, not the app.
 * It opens the school's own login flow and its own pages; it never stores a password, never fills
 * one in, and never exposes a JavaScript bridge.
 *
 * Routing rules:
 *  - school host, https            -> loaded here;
 *  - any other https               -> handed to the system browser (Custom Tabs when available);
 *  - anything http                 -> refused, because the app disables cleartext entirely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SchoolWebScreen(
    initialUrl: String,
    title: String,
    onClose: () -> Unit,
    onPageFinished: (url: String) -> Unit = {},
    onUrlChanged: (url: String) -> Unit = {},
    /**
     * When true the capture hook is installed into every page, so the requests the school's own
     * JavaScript makes can be recorded. Used by the "learn the endpoint" flow; never used for
     * ordinary browsing.
     */
    captureEnabled: Boolean = false,
    onFinishCapture: ((CaptureSession) -> Unit)? = null,
    /**
     * 顶部一行说明文字（可选）。登录流程用它提前告诉用户"成功后会自动返回"，
     * 免得用户输完密码后不知道该不该关掉这个页面。
     */
    hint: String? = null
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var sslError by remember { mutableStateOf<String?>(null) }
    var captureCount by remember { mutableIntStateOf(0) }
    // shouldInterceptRequest is called on a background thread for every frame, so the list is
    // synchronised rather than a Compose state.
    val intercepted = remember { java.util.Collections.synchronizedList(mutableListOf<InterceptedRequest>()) }
    // shouldInterceptRequest runs on a WebView thread, so the count is polled from an atomic rather
    // than written straight into Compose state.
    val interceptedCount = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    var requestCount by remember { mutableIntStateOf(0) }

    BackHandler(enabled = true) {
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else {
            onClose()
        }
    }

    // Poll the capture buffer: an XHR does not navigate, so onPageFinished alone would leave the
    // counter stuck at its initial value.
    LaunchedEffect(captureEnabled, webView) {
        if (!captureEnabled) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1_200)
            requestCount = interceptedCount.get()
            val view = webView ?: continue
            view.evaluateJavascript(CaptureScript.READ) { raw ->
                captureCount = CaptureScript.parseEvaluated(raw).size
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                runCatching {
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.destroy()
                }
                AppLog.d("webview destroyed")
            }
            webView = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1)
                        Text(
                            text = AllowedHosts.hostOf(currentUrl) ?: currentUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close))
                    }
                },
                actions = {
                    if (captureEnabled) {
                        IconButton(
                            onClick = {
                                val view = webView ?: return@IconButton
                                view.evaluateJavascript(CaptureScript.READ) { raw ->
                                    val session = CaptureSession(
                                        records = CaptureScript.parseEvaluated(raw),
                                        requests = synchronized(intercepted) { intercepted.toList() }
                                    )
                                    onFinishCapture?.invoke(session)
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "完成并分析",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    IconButton(onClick = { openExternally(context, currentUrl) }) {
                        Icon(
                            Icons.Default.OpenInBrowser,
                            contentDescription = stringResource(R.string.action_open_web)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (captureEnabled) {
                    Text(
                        text = "抓包中：XHR $captureCount 个 · 页面请求 $requestCount 个。" +
                            "请在页面里打开一次课表，然后点右上角 ✓ 完成。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.configureForSchool()
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?
                                ) {
                                    sslError = null
                                    progress = 1
                                    if (captureEnabled) {
                                        view?.evaluateJavascript(CaptureScript.INSTALL, null)
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    progress = 100
                                    if (captureEnabled) {
                                        view?.evaluateJavascript(CaptureScript.INSTALL, null)
                                        view?.evaluateJavascript(CaptureScript.READ) { raw ->
                                            captureCount = CaptureScript.parseEvaluated(raw).size
                                        }
                                    }
                                    url?.let {
                                        currentUrl = it
                                        onPageFinished(it)
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val target = request?.url?.toString() ?: return false
                                    return handleNavigation(context, target)
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): android.webkit.WebResourceResponse? {
                                    if (captureEnabled && request != null) {
                                        val url = request.url?.toString().orEmpty()
                                        if (url.startsWith("https://")) {
                                            synchronized(intercepted) {
                                                if (intercepted.size < 400 &&
                                                    intercepted.none { it.url == url && it.method == request.method }
                                                ) {
                                                    intercepted += InterceptedRequest(
                                                        method = request.method ?: "GET",
                                                        url = url,
                                                        mainFrame = request.isForMainFrame
                                                    )
                                                    interceptedCount.set(intercepted.size)
                                                }
                                            }
                                        }
                                    }
                                    // Returning null keeps the default behaviour: load normally.
                                    return null
                                }

                                @Deprecated("Deprecated in Java")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    val target = url ?: return false
                                    return handleNavigation(context, target)
                                }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: android.net.http.SslError?
                                ) {
                                    // The plan forbids handler.proceed() here. A failed certificate
                                    // check is a hard stop, not a warning.
                                    AppLog.e("SSL error refused for ${AllowedHosts.hostOf(error?.url)}")
                                    handler?.cancel()
                                    sslError = error?.let { "${it.primaryError}" } ?: "unknown"
                                    progress = 100
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        AppLog.w("web load error ${error?.errorCode} on ${AllowedHosts.hostOf(request.url.toString())}")
                                    }
                                }
                            }
                            loadUrl(initialUrl)
                            webView = this
                        }
                    },
                    update = { view ->
                        webView = view
                    }
                )
            }

            if (progress in 1..99 && webView == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            sslError?.let { code ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.web_ssl_error),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.web_ssl_error_hint),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(text = "code=$code", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * Returns true when the WebView should **not** load [target] itself.
 *
 * School hosts stay in the app; other https URLs go to the system browser; http is refused.
 */
private fun handleNavigation(context: Context, target: String): Boolean {
    if (AllowedHosts.isCleartext(target)) {
        AppLog.w("refused cleartext navigation to ${AllowedHosts.hostOf(target)}")
        return true
    }
    if (AllowedHosts.isAllowed(target)) return false
    openExternally(context, target)
    return true
}

/** Opens [url] in Custom Tabs when available, otherwise in the default browser. */
fun openExternally(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        AppLog.w("no browser available for external link")
    }
}
