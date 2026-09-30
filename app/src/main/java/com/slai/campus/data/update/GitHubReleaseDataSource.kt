package com.slai.campus.data.update

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.Redactor
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.network.UpdateClient
import com.slai.campus.core.session.SessionStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.slai.campus.domain.update.AppRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

data class ReleaseFetch(val result: RemoteResult<AppRelease>, val etag: String?)

/** Static manifest first. Only a missing feed (404) uses the legacy API during migration. */
@Singleton
class GitHubReleaseDataSource @Inject constructor(
    @UpdateClient private val client: OkHttpClient,
    private val store: SessionStore
) {
    suspend fun fetchLatest(): ReleaseFetch = withContext(Dispatchers.IO) {
        try {
            val manifest = fetch(UpdateManifest.URL, UpdateManifest::parse)
            if ((manifest.result as? RemoteResult.ServerError)?.code == 404) {
                fetch(LATEST_URL, ReleaseParser::parse)
            } else manifest
        } catch (_: UnknownHostException) {
            ReleaseFetch(RemoteResult.NetworkUnavailable("DNS 解析失败"), null)
        } catch (_: SocketTimeoutException) {
            ReleaseFetch(RemoteResult.NetworkUnavailable("连接超时"), null)
        } catch (e: IOException) {
            ReleaseFetch(RemoteResult.NetworkUnavailable(e.javaClass.simpleName), null)
        } catch (e: Exception) {
            AppLog.e("update check failed: ${e.javaClass.simpleName}")
            ReleaseFetch(RemoteResult.UnknownError(e.javaClass.simpleName), null)
        }
    }

    private suspend fun fetch(url: String, parse: (String?) -> AppRelease?): ReleaseFetch {
        val (cachedSource, cachedEtag, payload) = store.updateCache()
        val cached = if (cachedSource == url) runCatching {
            Json.decodeFromString<AppRelease>(payload.orEmpty())
        }.getOrNull() else null
        val fetch = readReleaseFeed(client, url, cached, cachedEtag, parse)
        (fetch.result as? RemoteResult.Success)?.let {
            store.setUpdateCache(url, fetch.etag, Json.encodeToString(it.data))
        }
        return fetch
    }

    /** 纯文本资产（目前只有 `SHA256SUMS.txt`）。失败返回 null，由调用方决定是否硬失败。 */
    suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.onFailure { AppLog.w("fetch asset failed: ${Redactor.redactUrl(url)} ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    private companion object {
        const val LATEST_URL = "https://api.github.com/repos/wangjt23/SLAIer-APP/releases/latest"
        const val USER_AGENT = "SLAIer-Android"
    }
}
