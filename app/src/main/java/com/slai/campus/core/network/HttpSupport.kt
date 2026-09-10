package com.slai.campus.core.network

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.Redactor
import com.slai.campus.core.common.RemoteResult
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Shared HTTP execution + failure classification.
 *
 * Every native read in this app goes through here so that "the request failed" always becomes one of
 * the five cases the UI knows how to explain, instead of a bare exception that callers might swallow
 * into an empty list.
 */
class RemoteHttpClient(private val client: OkHttpClient) {

    /** Executes [request] and hands the body to [parse] only on a 2xx response. */
    fun <T> execute(request: Request, parse: (Response) -> RemoteResult<T>): RemoteResult<T> {
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> parse(response)
                    response.code == 401 || response.code == 403 -> RemoteResult.SessionExpired
                    else -> RemoteResult.ServerError(response.code, response.message.ifBlank { null })
                }
            }
        } catch (e: UnknownHostException) {
            RemoteResult.NetworkUnavailable("DNS 解析失败")
        } catch (e: SocketTimeoutException) {
            RemoteResult.NetworkUnavailable("连接超时")
        } catch (e: SSLException) {
            RemoteResult.UnknownError("TLS 校验失败：${e.javaClass.simpleName}")
        } catch (e: IOException) {
            RemoteResult.NetworkUnavailable(e.javaClass.simpleName)
        } catch (e: Exception) {
            AppLog.e("unexpected HTTP failure: ${e.javaClass.simpleName}")
            RemoteResult.UnknownError(e.javaClass.simpleName)
        }
    }

    /** Same as [execute] but for callers that need to inspect the status code themselves. */
    fun raw(request: Request): Result<Response> = runCatching { client.newCall(request).execute() }
}

/** Builds the two clients the app needs. */
object HttpClientFactory {

    /**
     * Follows redirects. Used when loading an HTML page whose redirects are all part of the normal
     * navigation (the school's SSO dance).
     */
    fun webClient(builder: OkHttpClient.Builder, interceptor: WebViewCookieInterceptor): OkHttpClient =
        builder
            .addInterceptor(interceptor)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    /**
     * Does **not** follow redirects. This is what makes session-expiry detection reliable: an
     * unauthenticated ZFSoft request answers `302 -> /yjsxt/xtgl/login_slogin.html`, and hiding that
     * behind an automatic redirect would turn a clear "logged out" into an ambiguous HTML page.
     */
    fun apiClient(builder: OkHttpClient.Builder, interceptor: WebViewCookieInterceptor): OkHttpClient =
        builder
            .addInterceptor(interceptor)
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

    fun Request.Builder.get(url: HttpUrl): Request.Builder = url(url).get()

    fun Request.Builder.postForm(url: HttpUrl, fields: Map<String, String>): Request.Builder =
        url(url).post(okhttp3.FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build())

    /** `Content-Type` without parameters, lowercased. */
    fun Response.contentTypeBase(): String? =
        header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()

    fun Response.finalUrl(): String? = request.url.toString()

    fun Response.isHtml(): Boolean = contentTypeBase()?.contains("html") == true

    fun Response.isJson(): Boolean = contentTypeBase()?.contains("json") == true

    fun Headers.asMap(): Map<String, List<String>> =
        names().associateWith { name -> values(name) }
}
