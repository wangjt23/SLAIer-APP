package com.slai.campus.data.stu

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.IoDispatcher
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.web.WebViewExtractor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the check-in state out of the student-affairs web UI.
 *
 * Same two-step approach as the timetable: prefer a JSON payload the page fetched for itself, fall
 * back to the rendered text. Anything ambiguous returns [StuCheckInAnswer.UNKNOWN], which the UI
 * renders as "当前无法确认" — never as "未打卡".
 */
@Singleton
class StuWebViewDataSource @Inject constructor(
    private val extractor: WebViewExtractor,
    @IoDispatcher private val io: CoroutineDispatcher
) {

    data class Outcome(
        val result: RemoteResult<StuCheckInAnswer>,
        val url: String?,
        val note: String
    )

    suspend fun fetchCheckIn(baseUrl: String): Outcome = withContext(io) {
        val notes = StringBuilder()
        var sawAnyPage = false

        for (template in StuConfig.checkInPageCandidates) {
            val url = StuConfig.url(baseUrl, template)
            val capture = runCatching { extractor.capture(url) }.getOrElse { error ->
                notes.appendLine("$url -> ${error.javaClass.simpleName}")
                continue
            }
            notes.appendLine(
                "$url -> final=${capture.finalUrl} bodies=${capture.capturedBodies.size} err=${capture.error}"
            )

            if (capture.finalUrl != null && StuCheckInParser.isLoginUrl(capture.finalUrl)) {
                return@withContext Outcome(RemoteResult.SessionExpired, url, notes.toString())
            }
            if (capture.bodyText?.let { StuCheckInParser.looksLikeLoginPage(it) } == true) {
                return@withContext Outcome(RemoteResult.SessionExpired, url, notes.toString())
            }
            sawAnyPage = true

            // Prefer a JSON body the page fetched itself.
            capture.capturedBodies.firstOrNull { it.isJson && it.body.length > 2 }?.let { entry ->
                val classified = StuCheckInParser.classify(entry.body, "application/json")
                if (classified is RemoteResult.Success && classified.data != StuCheckInAnswer.UNKNOWN) {
                    return@withContext Outcome(classified, url, notes.toString())
                }
            }

            // Then the rendered page text.
            capture.bodyText?.let { text ->
                val classified = StuCheckInParser.classify(text, "text/html")
                if (classified is RemoteResult.Success && classified.data != StuCheckInAnswer.UNKNOWN) {
                    return@withContext Outcome(classified, url, notes.toString())
                }
            }
        }

        val result: RemoteResult<StuCheckInAnswer> = when {
            !sawAnyPage -> RemoteResult.SchemaChanged("没有可用的打卡页面")
            else -> RemoteResult.Success(StuCheckInAnswer.UNKNOWN)
        }
        AppLog.d("STU webview check-in notes:\n$notes")
        Outcome(result, null, notes.toString())
    }
}
