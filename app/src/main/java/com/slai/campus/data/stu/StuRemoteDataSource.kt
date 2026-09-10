package com.slai.campus.data.stu

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.IoDispatcher
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.network.HttpSupport.asMap
import com.slai.campus.core.network.HttpSupport.contentTypeBase
import com.slai.campus.core.network.NetworkMonitor
import com.slai.campus.core.network.StuApiClient
import com.slai.campus.core.network.StuWebClient
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.session.SessionStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native access to the student-affairs system.
 *
 * Ships as a *probe* rather than a working integration: the check-in endpoint has not been verified
 * against the live system (that requires a real student login), so nothing here is wired into the
 * home screen until a candidate passes validation. If none does, the app keeps
 * `IntegrationMode.WEB_ONLY` and the check-in card offers the original web page.
 */
@Singleton
class StuRemoteDataSource @Inject constructor(
    @StuApiClient private val apiClient: OkHttpClient,
    @StuWebClient private val webClient: OkHttpClient,
    private val networkMonitor: NetworkMonitor,
    private val sessionStore: SessionStore,
    @IoDispatcher private val io: CoroutineDispatcher
) {

    data class CheckInOutcome(
        val result: RemoteResult<StuCheckInAnswer>,
        val endpoint: String?,
        val trace: String
    )

    suspend fun probeSession(baseUrl: String): SessionState = withContext(io) {
        if (!networkMonitor.hasNetwork) return@withContext SessionState.ERROR

        for (template in StuConfig.sessionProbeCandidates) {
            val url = StuConfig.url(baseUrl, template)
            val request = Request.Builder().url(url).get()
                .header("Accept", "text/html,application/json")
                .build()
            val response = runCatching { apiClient.newCall(request).execute() }.getOrElse {
                AppLog.w("STU probe transport failure: ${it.javaClass.simpleName}")
                return@withContext SessionState.ERROR
            }
            response.use { res ->
                val location = res.header("Location")
                when {
                    res.code in 300..399 && StuCheckInParser.isLoginUrl(location) -> return@withContext SessionState.EXPIRED
                    res.code in 300..399 -> continue
                    res.code == 401 || res.code == 403 -> return@withContext SessionState.EXPIRED
                    res.code == 200 -> {
                        val body = runCatching { res.peekBody(8 * 1024).string() }.getOrDefault("")
                        // JeeSite answers 200 with the login page when a session is missing.
                        return@withContext if (StuCheckInParser.looksLikeLoginPage(body)) {
                            SessionState.EXPIRED
                        } else {
                            SessionState.AUTHENTICATED
                        }
                    }
                    res.code == 404 -> continue
                    else -> continue
                }
            }
        }
        SessionState.ERROR
    }

    /** Tries each candidate check-in endpoint; the first valid answer wins. */
    suspend fun fetchCheckIn(baseUrl: String): CheckInOutcome = withContext(io) {
        if (!networkMonitor.hasNetwork) {
            return@withContext CheckInOutcome(RemoteResult.NetworkUnavailable("设备无网络"), null, "")
        }

        val log = StringBuilder()
        var lastResult: RemoteResult<Nothing>? = null

        for (template in StuConfig.checkInApiCandidates) {
            val url = StuConfig.url(baseUrl, template)
            val request = Request.Builder().url(url).get()
                .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val response = runCatching { apiClient.newCall(request).execute() }.getOrElse { error ->
                log.appendLine("$url -> transport ${error.javaClass.simpleName}")
                return@withContext CheckInOutcome(
                    RemoteResult.NetworkUnavailable(error.javaClass.simpleName),
                    url,
                    log.toString()
                )
            }

            val outcome = response.use { res ->
                val text = runCatching { res.peekBody(1024L * 1024).string() }.getOrDefault("")
                log.appendLine("$url -> ${res.code} ${res.contentTypeBase() ?: ""} (${text.length}B)")
                when {
                    res.code in 300..399 && StuCheckInParser.isLoginUrl(res.header("Location")) ->
                        RemoteResult.SessionExpired
                    res.code == 401 || res.code == 403 -> RemoteResult.SessionExpired
                    res.code == 404 -> RemoteResult.SchemaChanged("endpoint not found")
                    !res.isSuccessful -> RemoteResult.ServerError(res.code, null)
                    StuCheckInParser.looksLikeLoginPage(text) -> RemoteResult.SessionExpired
                    else -> StuCheckInParser.classify(text, res.contentTypeBase())
                }
            }

            when (outcome) {
                is RemoteResult.Success -> {
                    // UNKNOWN means the endpoint answered but we could not read a state from it;
                    // keep trying other candidates before giving up.
                    if (outcome.data != StuCheckInAnswer.UNKNOWN) {
                        sessionStore.setIntegrationMode(SchoolSystem.STU, com.slai.campus.core.common.IntegrationMode.NATIVE_API)
                        return@withContext CheckInOutcome(outcome, url, log.toString())
                    }
                    lastResult = RemoteResult.SchemaChanged("候选接口返回无法判定的内容")
                }
                RemoteResult.SessionExpired -> return@withContext CheckInOutcome(outcome, url, log.toString())
                is RemoteResult.NetworkUnavailable -> return@withContext CheckInOutcome(outcome, url, log.toString())
                is RemoteResult.ServerError -> lastResult = outcome
                is RemoteResult.SchemaChanged -> lastResult = outcome
                is RemoteResult.UnknownError -> lastResult = outcome
            }
        }

        CheckInOutcome(
            result = lastResult ?: RemoteResult.SchemaChanged("没有可用的打卡接口"),
            endpoint = null,
            trace = log.toString()
        )
    }

    /** Loads a page body through the redirect-following client (used by the diagnostics probe). */
    suspend fun fetchPage(url: String): RemoteResult<String> = withContext(io) {
        val request = Request.Builder().url(url).get()
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        val response = runCatching { webClient.newCall(request).execute() }.getOrElse { error ->
            return@withContext RemoteResult.NetworkUnavailable(error.javaClass.simpleName)
        }
        response.use { res ->
            val text = runCatching { res.body?.string() }.getOrNull().orEmpty()
            val result: RemoteResult<String> = when {
                !res.isSuccessful -> RemoteResult.ServerError(res.code, null)
                StuCheckInParser.looksLikeLoginPage(text) -> RemoteResult.SessionExpired
                else -> RemoteResult.Success(text)
            }
            result
        }
    }

    suspend fun headersOf(url: String): Map<String, List<String>> = withContext(io) {
        val request = Request.Builder().url(url).get().build()
        runCatching { apiClient.newCall(request).execute() }.getOrNull()?.use { it.headers.asMap() } ?: emptyMap()
    }
}
