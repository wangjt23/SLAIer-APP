package com.slai.campus.data.stu

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.IoDispatcher
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.network.HttpSupport.contentTypeBase
import com.slai.campus.core.network.NetworkMonitor
import com.slai.campus.core.network.StuApiClient
import com.slai.campus.domain.attendance.AttendancePunch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 读取「学生刷卡记录表」（单个闸机进出记录）。
 *
 * Endpoint confirmed by capture (2026-09-09):
 * ```
 * GET /a/edu/acm/swipe/listData?page=1&limit=10&startTime=&endTime=
 * → {"code":0,"count":102,"data":[{swipeTime, eventType(进门/出门), channelName, …}]}
 * ```
 *
 * 这是**唯一**能拿到具体进出时间的接口：`weekGroupedByMonth` 的 `firstSwipe`/`swipeTimes` 都是空，
 * 只有它自己的「当日累计时长」字段，而且当天的值恒为 0（未结算），所以当天时长必须自己按进出算。
 */
@Singleton
class StuPunchDataSource @Inject constructor(
    @StuApiClient private val apiClient: OkHttpClient,
    private val networkMonitor: NetworkMonitor,
    @IoDispatcher private val io: CoroutineDispatcher
) {

    data class Outcome(
        val result: RemoteResult<List<AttendancePunch>>,
        val trace: String
    )

    /**
     * 拉取 [from]..[to] 的刷卡记录。
     *
     * 先带 `startTime`/`endTime`（`yyyy-MM-dd`）查询；如果服务端不接受该格式（返回空），
     * 再不带过滤拉全量并在本地按日期过滤。
     */
    suspend fun fetchPunches(
        baseUrl: String,
        from: LocalDate,
        to: LocalDate
    ): Outcome = withContext(io) {
        if (!networkMonitor.hasNetwork) {
            return@withContext Outcome(RemoteResult.NetworkUnavailable("设备无网络"), "")
        }

        val filtered = request(baseUrl, from, to)
        val first = filtered.first
        if (first is RemoteResult.Success && first.data.isNotEmpty()) {
            val inRange = first.data.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
            if (inRange.isNotEmpty()) return@withContext Outcome(RemoteResult.Success(inRange), filtered.second)
        }

        // 回退：不带日期过滤。
        val all = request(baseUrl, null, null)
        when (val result = all.first) {
            is RemoteResult.Success -> {
                val inRange = result.data.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
                Outcome(RemoteResult.Success(inRange), filtered.second + "\n" + all.second)
            }
            else -> Outcome(result, filtered.second + "\n" + all.second)
        }
    }

    private suspend fun request(
        baseUrl: String,
        from: LocalDate?,
        to: LocalDate?
    ): Pair<RemoteResult<List<AttendancePunch>>, String> {
        val url: HttpUrl = runCatching {
            StuConfig.url(baseUrl, StuConfig.PUNCH_LIST_API).toHttpUrl().newBuilder()
                .addQueryParameter("page", "1")
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .addQueryParameter("startTime", from?.toString().orEmpty())
                .addQueryParameter("endTime", to?.toString().orEmpty())
                .build()
        }.getOrElse { error ->
            return RemoteResult.UnknownError("URL 构造失败：${error.javaClass.simpleName}") to ""
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", StuConfig.url(baseUrl, StuConfig.PUNCH_PAGE))
            .build()

        val started = System.currentTimeMillis()
        val response = runCatching { apiClient.newCall(request).execute() }.getOrElse { error ->
            AppLog.w("punch transport failure: ${error.javaClass.simpleName}")
            return RemoteResult.NetworkUnavailable(error.javaClass.simpleName) to
                "transport error: ${error.javaClass.simpleName}"
        }

        response.use { res ->
            val text = runCatching { res.peekBody(8L * 1024 * 1024).string() }.getOrNull().orEmpty()
            val trace = buildString {
                appendLine("GET ${StuConfig.PUNCH_LIST_API}?page=1&limit=$PAGE_SIZE&startTime=${from ?: ""}&endTime=${to ?: ""}")
                appendLine("status: ${res.code}  ct: ${res.contentTypeBase() ?: "—"}")
                appendLine("bytes: ${text.length}  ${System.currentTimeMillis() - started}ms")
                appendLine("preview: ${com.slai.campus.core.common.Redactor.preview(text, 240)}")
            }
            val result: RemoteResult<List<AttendancePunch>> = when {
                res.code in 300..399 && StuCheckInParser.isLoginUrl(res.header("Location")) ->
                    RemoteResult.SessionExpired
                res.code == 401 || res.code == 403 -> RemoteResult.SessionExpired
                !res.isSuccessful -> RemoteResult.ServerError(res.code, res.message.ifBlank { null })
                StuCheckInParser.looksLikeLoginPage(text) -> RemoteResult.SessionExpired
                else -> StuPunchParser.parse(text)
            }
            return result to trace
        }
    }

    companion object {
        private const val PAGE_SIZE = 500
    }
}
