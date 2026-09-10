package com.slai.campus.data.provider

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.IoDispatcher
import com.slai.campus.core.common.Redactor
import com.slai.campus.core.network.SisApiClient
import com.slai.campus.domain.provider.ApiProvider
import com.slai.campus.domain.schedule.Semester
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** Raw outcome of executing a provider. */
data class ProviderResponse(
    val status: Int?,
    val contentType: String?,
    val body: String?,
    val finalUrl: String?,
    val durationMs: Long,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null && status != null && status in 200..299

    /** The school answers 901 (empty body) for a request it refuses, e.g. an unauthenticated POST. */
    val isRefused: Boolean get() = status == 901

    fun render(): String = buildString {
        appendLine("${status ?: "—"}${error?.let { " ($it)" } ?: ""}  ${durationMs}ms")
        if (isRefused) appendLine("note: 901 = 服务器拒绝（未授权或请求被拦截）")
        appendLine("content-type: ${contentType ?: "—"}")
        finalUrl?.let { appendLine("final: ${Redactor.redactUrl(it)}") }
        body?.let { appendLine("body: ${Redactor.preview(it, 400)}") }
    }
}

/**
 * Executes a provider definition through the cookie-bridged OkHttp client.
 *
 * Uses the non-redirecting client on purpose: a `302` to the login page is the signal that the
 * session is gone, and hiding it behind an automatic redirect would turn a clear "logged out" into an
 * unparseable HTML body.
 */
@Singleton
class ProviderExecutor @Inject constructor(
    @SisApiClient private val apiClient: OkHttpClient,
    @IoDispatcher private val io: CoroutineDispatcher
) {

    suspend fun execute(provider: ApiProvider, semester: Semester? = null): ProviderResponse =
        withContext(io) {
            val url = substitute(provider.url, semester)
            val bodyText = provider.body?.let { substitute(it, semester) }

            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")

            provider.headers.forEach { (name, value) ->
                runCatching { builder.header(name, value) }
            }

            if (provider.method.equals("POST", ignoreCase = true)) {
                val mediaType = (provider.contentType ?: "application/x-www-form-urlencoded; charset=UTF-8")
                    .toMediaType()
                builder.post((bodyText ?: "").toRequestBody(mediaType))
            } else {
                builder.get()
            }

            val started = System.currentTimeMillis()
            val response = runCatching { apiClient.newCall(builder.build()).execute() }.getOrElse { error ->
                AppLog.w("provider transport failure: ${error.javaClass.simpleName}")
                return@withContext ProviderResponse(
                    status = null,
                    contentType = null,
                    body = null,
                    finalUrl = url,
                    durationMs = System.currentTimeMillis() - started,
                    error = error.javaClass.simpleName
                )
            }

            response.use { res ->
                val text = runCatching { res.peekBody(4L * 1024 * 1024).string() }.getOrNull()
                ProviderResponse(
                    status = res.code,
                    contentType = res.header("Content-Type"),
                    body = text,
                    finalUrl = res.request.url.toString(),
                    durationMs = System.currentTimeMillis() - started
                )
            }
        }

    /**
     * Replaces `{{xnm}}` / `{{xqm}}` with the current term codes.
     *
     * Learned request bodies contain the term they were captured in; templating them means the
     * provider keeps working next semester instead of silently returning the wrong term's timetable.
     */
    private fun substitute(template: String, semester: Semester?): String {
        var out = template
        semester?.academicYear?.takeIf { it.isNotBlank() }?.let { out = out.replace("{{xnm}}", it) }
        semester?.termCode?.takeIf { it.isNotBlank() }?.let { out = out.replace("{{xqm}}", it) }
        return out
    }
}
