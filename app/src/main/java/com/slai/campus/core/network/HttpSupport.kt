package com.slai.campus.core.network

import com.slai.campus.core.common.Redactor
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response

/** Builds the two clients the app needs. */
object HttpClientFactory {

    /**
     * Follows redirects. Used when loading an HTML page whose redirects are all part of the normal
     * navigation (the school's SSO dance).
     */
    fun webClient(builder: OkHttpClient.Builder, interceptor: okhttp3.Interceptor,
                  requestGuard: okhttp3.Interceptor? = null): OkHttpClient =
        builder.build().newBuilder()
            .apply { requestGuard?.let { addInterceptor(it) } }
            .addNetworkInterceptor(interceptor)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    /**
     * Does **not** follow redirects. This is what makes session-expiry detection reliable: an
     * unauthenticated ZFSoft request answers `302 -> /yjsxt/xtgl/login_slogin.html`, and hiding that
     * behind an automatic redirect would turn a clear "logged out" into an ambiguous HTML page.
     */
    fun apiClient(builder: OkHttpClient.Builder, interceptor: okhttp3.Interceptor,
                  requestGuard: okhttp3.Interceptor? = null): OkHttpClient =
        builder.build().newBuilder()
            .apply { requestGuard?.let { addInterceptor(it) } }
            .addNetworkInterceptor(interceptor)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
}

/** Redacted, diagnostics-friendly view of a request/response pair. */
data class HttpTrace(
    val method: String,
    val url: String,
    val status: Int?,
    val contentType: String?,
    val bodyPreview: String?,
    val headers: Map<String, List<String>>,
    val finalUrl: String?,
    val durationMs: Long,
    val failure: String?
) {
    fun render(): String = buildString {
        appendLine("$method ${Redactor.redactUrl(url)}")
        appendLine("status: ${status ?: "—"}${failure?.let { " ($it)" } ?: ""}")
        if (finalUrl != null && finalUrl != url) appendLine("final: ${Redactor.redactUrl(finalUrl)}")
        appendLine("content-type: ${contentType ?: "—"}")
        appendLine("duration: ${durationMs}ms")
        if (headers.isNotEmpty()) {
            appendLine("headers:")
            Redactor.redactHeaders(headers).forEach { (k, v) -> appendLine("  $k: ${v.joinToString(", ")}") }
        }
        if (!bodyPreview.isNullOrBlank()) {
            appendLine("body preview:")
            appendLine("  $bodyPreview")
        }
    }
}

/** Small helpers shared by the data sources. */
object HttpSupport {

    /** `Content-Type` without parameters, lowercased. */
    fun Response.contentTypeBase(): String? =
        header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()

    fun Headers.asMap(): Map<String, List<String>> =
        names().associateWith { name -> values(name) }
}
