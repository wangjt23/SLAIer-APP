package com.slai.campus.data.stu

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.IoDispatcher
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.core.network.HttpSupport.contentTypeBase
import com.slai.campus.core.network.NetworkMonitor
import com.slai.campus.core.network.StuApiClient
import com.slai.campus.domain.attendance.AttendanceMonth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the attendance ledger from the student-affairs system.
 *
 * Endpoint confirmed by a real capture (2026-09-09):
 * ```
 * POST https://stu.slai.edu.cn/a/edu/acm/swipe/weekGroupedByMonth
 * body: startMonth=2026-09&cycleWeek=
 * → {"weeks":[…],"data":{"week1":[…]},"stats":{…},"success":true}
 * ```
 * The page it belongs to is titled 学生考勤统计查询 and lives at
 * `/a/edu/acm/swipe/attendList`.
 */
@Singleton
class StuAttendanceDataSource @Inject constructor(
    @StuApiClient private val apiClient: OkHttpClient,
    private val networkMonitor: NetworkMonitor,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val io: CoroutineDispatcher
) {

    data class Outcome(
        val result: RemoteResult<AttendanceMonth>,
        val trace: String
    )

    suspend fun fetchMonth(baseUrl: String, month: String): Outcome = withContext(io) {
        if (!networkMonitor.hasNetwork) {
            return@withContext Outcome(RemoteResult.NetworkUnavailable("设备无网络"), "")
        }

        val url = StuConfig.url(baseUrl, StuConfig.ATTENDANCE_API)
        val body = FormBody.Builder()
            .add("startMonth", month)
            .add("cycleWeek", "")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Referer", StuConfig.url(baseUrl, StuConfig.ATTENDANCE_PAGE))
            .build()

        val started = System.currentTimeMillis()
        val response = runCatching { apiClient.newCall(request).execute() }.getOrElse { error ->
            AppLog.w("attendance transport failure: ${error.javaClass.simpleName}")
            return@withContext Outcome(
                RemoteResult.NetworkUnavailable(error.javaClass.simpleName),
                "transport error: ${error.javaClass.simpleName}"
            )
        }

        response.use { res ->
            val text = runCatching { res.peekBody(4L * 1024 * 1024).string() }.getOrNull().orEmpty()
            val trace = buildString {
                appendLine("POST ${StuConfig.ATTENDANCE_API}")
                appendLine("body: startMonth=$month&cycleWeek=")
                appendLine("status: ${res.code}  ct: ${res.contentTypeBase() ?: "—"}")
                appendLine("bytes: ${text.length}  ${System.currentTimeMillis() - started}ms")
                appendLine("preview: ${com.slai.campus.core.common.Redactor.preview(text, 240)}")
            }

            val result = when {
                res.code in 300..399 && StuCheckInParser.isLoginUrl(res.header("Location")) ->
                    RemoteResult.SessionExpired
                res.code == 401 || res.code == 403 -> RemoteResult.SessionExpired
                !res.isSuccessful -> RemoteResult.ServerError(res.code, res.message.ifBlank { null })
                StuCheckInParser.looksLikeLoginPage(text) -> RemoteResult.SessionExpired
                else -> StuAttendanceParser.parse(text, month)
            }
            return@withContext Outcome(result, trace)
        }
    }

    /** Current month in `YYYY-MM`. */
    fun currentMonth(): String = timeProvider.today().let { "%04d-%02d".format(it.year, it.monthValue) }

    companion object {
        fun monthOf(date: java.time.LocalDate): String = "%04d-%02d".format(date.year, date.monthValue)

        fun isValidMonth(month: String): Boolean =
            Regex("^\\d{4}-\\d{2}$").matches(month) && month.substring(5).toIntOrNull() in 1..12
    }
}
