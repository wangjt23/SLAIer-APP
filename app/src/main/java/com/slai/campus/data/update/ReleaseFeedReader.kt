package com.slai.campus.data.update

import com.slai.campus.core.common.RemoteResult
import com.slai.campus.domain.update.AppRelease
import okhttp3.OkHttpClient
import okhttp3.Request

/** Read a feed without Android state, so HTTP cache behavior can be tested with real OkHttp. */
internal fun readReleaseFeed(
    client: OkHttpClient,
    url: String,
    cached: AppRelease?,
    cachedEtag: String?,
    parse: (String?) -> AppRelease?
): ReleaseFetch {
    val etag = cachedEtag.takeIf { cached != null }
    repeat(2) { attempt ->
        val request = Request.Builder().url(url)
            .header("Accept", if (url.startsWith("https://api.github.com/")) "application/vnd.github+json" else "application/json")
            .header("User-Agent", "SLAIer-Android")
            .apply { if (attempt == 0 && !etag.isNullOrBlank()) header("If-None-Match", etag) }
            .get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 304) {
                if (cached != null) return ReleaseFetch(RemoteResult.Success(cached), etag)
                if (attempt == 0) return@use
            }
            if (!response.isSuccessful) return ReleaseFetch(
                RemoteResult.ServerError(response.code, response.message.ifBlank { null }), etag)
            val release = parse(response.body?.string()) ?: return ReleaseFetch(
                RemoteResult.SchemaChanged("更新清单结构不认识"), etag)
            return ReleaseFetch(RemoteResult.Success(release), response.header("ETag"))
        }
    }
    return ReleaseFetch(RemoteResult.SchemaChanged("更新清单缓存不可用"), null)
}
