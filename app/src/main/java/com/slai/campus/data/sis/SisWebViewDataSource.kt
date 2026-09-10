package com.slai.campus.data.sis

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.IoDispatcher
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.common.failureOrNull
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.core.session.SessionStore
import com.slai.campus.core.web.DomTable
import com.slai.campus.core.web.WebCapture
import com.slai.campus.core.web.WebViewExtractor
import com.slai.campus.domain.schedule.ClassOccurrence
import com.slai.campus.domain.schedule.ScheduleSource
import com.slai.campus.domain.schedule.Semester
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the timetable out of the school's own timetable page.
 *
 * Two independent paths, tried in this order:
 *  1. **XHR capture** — the page's own request to the timetable endpoint is intercepted by an
 *     in-page hook, so we never have to guess request parameters, and the payload is the same JSON
 *     the native path would have fetched. This is the reliable one.
 *  2. **DOM scrape** — if the page renders server-side HTML instead of JSON, the biggest tables are
 *     scraped and interpreted heuristically. Labelled `SIS_WEBVIEW_DOM` so the UI can say the data
 *     came from a lower-fidelity path.
 */
@Singleton
class SisWebViewDataSource @Inject constructor(
    private val extractor: WebViewExtractor,
    private val sessionStore: SessionStore,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val io: CoroutineDispatcher
) {

    suspend fun fetchTimetable(
        baseUrl: String,
        semester: Semester,
        accountHash: String
    ): RemoteResult<SisTimetableParsed> = withContext(io) {
        var lastError: RemoteResult<Nothing>? = null
        val notes = StringBuilder()

        for (template in SisConfig.timetablePageCandidates) {
            val url = SisConfig.apiUrl(baseUrl, template)
            val capture = runCatching { extractor.capture(url) }.getOrElse { error ->
                AppLog.w("webview capture failed: ${error.javaClass.simpleName}")
                notes.appendLine("$url -> ${error.javaClass.simpleName}")
                continue
            }
            notes.appendLine("$url -> final=${capture.finalUrl} bodies=${capture.capturedBodies.size} tables=${capture.tables.size} err=${capture.error}")

            if (capture.finalUrl != null && SisScheduleParser.isLoginUrl(capture.finalUrl)) {
                return@withContext RemoteResult.SessionExpired
            }
            if (capture.bodyText?.let { SisScheduleParser.looksLikeLoginPage(it) } == true) {
                return@withContext RemoteResult.SessionExpired
            }

            // Path 1: the page's own XHR answered with timetable JSON.
            xhrPayload(capture)?.let { payload ->
                val parsed = SisScheduleParser.parse(
                    body = payload,
                    semester = semester,
                    source = ScheduleSource.SIS_WEBVIEW_XHR,
                    accountHash = accountHash
                )
                if (parsed is RemoteResult.Success) return@withContext parsed
                lastError = parsed.failureOrNull()
            }

            // Path 2: scrape the rendered table. The grid shows one week, so its index must be
            // derived from today's date rather than assumed to be week 1.
            val weekIndex = semester.weekIndexOf(timeProvider.today()) ?: 1
            val domOccurrences = SisDomTimetableParser.parse(capture.tables, semester, weekIndex, accountHash)
            if (domOccurrences.isNotEmpty()) {
                return@withContext RemoteResult.Success(
                    SisTimetableParsed(
                        occurrences = domOccurrences,
                        response = SisTimetableResponse(),
                        rawItemCount = domOccurrences.size,
                        skippedRows = 0,
                        studentId = null,
                        warning = "数据来自页面渲染结果（DOM 提取）"
                    )
                )
            }

            if (lastError == null) {
                lastError = RemoteResult.SchemaChanged("页面未产生可解析的课表数据")
            }
        }

        AppLog.d("SIS webview extraction notes:\n$notes")
        lastError ?: RemoteResult.SchemaChanged("没有可用的课表页面")
    }

    /** Picks the first captured body that looks like a ZFSoft timetable payload. */
    private fun xhrPayload(capture: WebCapture): String? =
        capture.capturedBodies
            .filter { it.isJson }
            .firstOrNull { record ->
                val body = record.body
                body.contains("kbList") || body.contains("xqjmcMap") || body.contains("sjkList")
            }
            ?.body

    /** The teaching week containing today, when the semester is anchored. */
    suspend fun currentWeek(): Int? {
        val (anchor, _) = sessionStore.semesterAnchor()
        if (anchor == null) return null
        return Semester(firstWeekMonday = anchor).weekIndexOf(timeProvider.today())
    }
}

/**
 * Best-effort interpreter for a rendered ZFSoft timetable grid.
 *
 * ZFSoft renders a table whose first column holds the period label and whose remaining seven columns
 * are Monday..Sunday. Each populated cell contains the course name, teacher, room and the week
 * description, separated by line breaks that `innerText` collapses into spaces.
 *
 * Because the grid only ever shows one teaching week, occurrences are produced for that single week.
 * Anything ambiguous is skipped rather than guessed: a wrong class on a wrong day is worse than a
 * missing row, and the caller still has the JSON path and the web page as fallbacks.
 */
object SisDomTimetableParser {

    private val WEEK_PATTERN = Regex("""(\d{1,2}\s*-\s*\d{1,2}\s*周(?:\([单双]\))?|\d{1,2}\s*周(?:\([单双]\))?)""")
    private val PERIOD_LABEL = Regex("""^\s*(?:第)?\s*(\d{1,2})\s*(?:[-–~]\s*(\d{1,2}))?\s*节?\s*$""")

    fun parse(
        tables: List<DomTable>,
        semester: Semester,
        weekIndex: Int,
        accountHash: String
    ): List<ClassOccurrence> {
        val anchor = semester.firstWeekMonday ?: return emptyList()
        if (weekIndex !in 1..60) return emptyList()
        val periodTable = SisScheduleNormalizer.periodTable(null)

        val grid = tables.maxByOrNull { it.rows.sumOf { row -> row.size } } ?: return emptyList()
        if (grid.rows.size < 3) return emptyList()

        val occurrences = mutableListOf<ClassOccurrence>()

        grid.rows.forEachIndexed { rowIndex, row ->
            if (row.isEmpty()) return@forEachIndexed
            val label = row.firstOrNull()?.trim().orEmpty()
            val periodRange = parsePeriodLabel(label)
            // Row 0 is normally the weekday header; without a period label there is nothing to anchor.
            if (periodRange == null) return@forEachIndexed

            // Column 0 is the label; columns 1..7 are Monday..Sunday.
            for (column in 1 until minOf(row.size, 8)) {
                val cell = row[column].trim()
                if (cell.isEmpty()) continue
                val weekday = column
                parseCell(cell, periodRange, periodTable)?.let { parsed ->
                    val date = anchor.plusWeeks((weekIndex - 1).toLong()).plusDays((weekday - 1).toLong())
                    occurrences += ClassOccurrence(
                        id = "dom|${accountHash.take(8)}|$date|${parsed.courseName}|$weekday|${periodRange.first}",
                        courseName = parsed.courseName,
                        teacher = parsed.teacher,
                        location = parsed.location,
                        date = date,
                        startTime = parsed.startTime,
                        endTime = parsed.endTime,
                        source = ScheduleSource.SIS_WEBVIEW_DOM,
                        weekIndex = weekIndex,
                        periodStart = periodRange.first,
                        periodEnd = periodRange.last,
                        courseCode = null,
                        teachingClass = null,
                        campus = null
                    )
                }
            }
        }
        return occurrences.distinctBy { it.id }
    }

    private fun parsePeriodLabel(label: String): IntRange? {
        val match = PERIOD_LABEL.find(label) ?: return null
        val start = match.groupValues[1].toIntOrNull() ?: return null
        val end = match.groupValues[2].toIntOrNull() ?: start
        return start..end
    }

    private data class ParsedCell(
        val courseName: String,
        val teacher: String?,
        val location: String?,
        val startTime: java.time.LocalTime,
        val endTime: java.time.LocalTime
    )

    private fun parseCell(
        cell: String,
        periodRange: IntRange,
        periodTable: Map<Int, java.time.LocalTime>
    ): ParsedCell? {
        val text = cell.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        if (WEEK_PATTERN.find(text) == null) return null // a real cell always carries a week range

        // Drop the week fragment; what remains is "course teacher room" (order varies by build).
        val withoutWeeks = text.replace(WEEK_PATTERN, " ").replace(Regex("\\s+"), " ").trim()
        if (withoutWeeks.isEmpty()) return null

        val tokens = withoutWeeks.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        val courseName = tokens.first()
        // Heuristics for the remaining tokens: a room usually contains a digit, a teacher usually not.
        val rest = tokens.drop(1)
        val location = rest.firstOrNull { it.any(Char::isDigit) && it.length <= 20 }
        val teacher = rest.firstOrNull { it != location && it.length in 2..12 && it.none(Char::isDigit) }

        val start = periodTable[periodRange.first] ?: java.time.LocalTime.of(8, 0)
        val end = (periodTable[periodRange.last] ?: start).plusMinutes(45)
        return ParsedCell(
            courseName = courseName,
            teacher = teacher,
            location = location,
            startTime = start,
            endTime = end
        )
    }
}
