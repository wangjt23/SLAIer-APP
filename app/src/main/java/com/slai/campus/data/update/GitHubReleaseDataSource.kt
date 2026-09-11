package com.slai.campus.data.update

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.Redactor
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.network.UpdateClient
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

/** 一次检查的结果：`result.data == null` 表示 304（本地缓存仍然有效）。 */
data class ReleaseFetch(
    val result: RemoteResult<AppRelease?>,
    val etag: String?
)

/**
 * 从 GitHub Releases 读最新版本。
 *
 * 三个刻意的设计：
 *
 *  - 用 `ETag` + `If-None-Match`：命中 304 时不消耗解析、也几乎不消耗带宽；
 *  - **区分 403/429（限流）与其它失败**：未认证 API 是 60 次/小时/IP，校园网出口 NAT 下全班
 *    共用一个 IP，撞上限流非常正常 —— 这时应该安静地等下一次，而不是告诉用户"更新失败"；
 *  - 用**不含学校 cookie 的独立 OkHttp 客户端**（`@UpdateClient`）：往 GitHub 发请求绝不能
 *    带上 WebView 里的教务会话。
 */
@Singleton
class GitHubReleaseDataSource @Inject constructor(
    @UpdateClient private val client: OkHttpClient
) {

    suspend fun fetchLatest(etag: String?): ReleaseFetch = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> ReleaseFetch(RemoteResult.Success(null), etag)

                    response.code == 403 || response.code == 429 ->
                        ReleaseFetch(RemoteResult.ServerError(response.code, "rate limited"), etag)

                    response.isSuccessful -> {
                        val body = response.body?.string()
                        val release = ReleaseParser.parse(body)
                        if (release == null) {
                            ReleaseFetch(RemoteResult.SchemaChanged("releases/latest 结构不认识"), etag)
                        } else {
                            AppLog.i("update check: latest=${release.versionName}")
                            ReleaseFetch(RemoteResult.Success(release), response.header("ETag"))
                        }
                    }

                    else -> ReleaseFetch(
                        RemoteResult.ServerError(response.code, response.message.ifBlank { null }),
                        etag
                    )
                }
            }
        } catch (e: UnknownHostException) {
            ReleaseFetch(RemoteResult.NetworkUnavailable("DNS 解析失败"), etag)
        } catch (e: SocketTimeoutException) {
            ReleaseFetch(RemoteResult.NetworkUnavailable("连接超时"), etag)
        } catch (e: IOException) {
            ReleaseFetch(RemoteResult.NetworkUnavailable(e.javaClass.simpleName), etag)
        } catch (e: Exception) {
            AppLog.e("update check failed: ${e.javaClass.simpleName}")
            ReleaseFetch(RemoteResult.UnknownError(e.javaClass.simpleName), etag)
        }
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
