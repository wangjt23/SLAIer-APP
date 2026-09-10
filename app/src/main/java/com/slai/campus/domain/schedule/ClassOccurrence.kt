package com.slai.campus.domain.schedule

/**
 * A single teaching session pinned to a concrete calendar date and wall-clock time.
 *
 * This is the only shape the UI ever sees. All of the messy parts of the source system — week
 * ranges, odd/even weeks, period numbers, holiday adjustments — are resolved by the normalizer
 * before the data reaches the database. Compose screens never compute a week number.
 */
data class ClassOccurrence(
    /** Stable identity: `system|accountHash|date|start|courseCode|className|period`. */
    val id: String,
    val courseName: String,
    val teacher: String? = null,
    val location: String? = null,
    val date: java.time.LocalDate,
    val startTime: java.time.LocalTime,
    val endTime: java.time.LocalTime,
    val source: ScheduleSource,
    /** 1-based teaching week within the semester, when known. */
    val weekIndex: Int? = null,
    /** Period numbers (节次) as reported by the source system, e.g. 3..4. */
    val periodStart: Int? = null,
    val periodEnd: Int? = null,
    val courseCode: String? = null,
    /** Teaching-class name (教学班), useful when the same course name appears twice. */
    val teachingClass: String? = null,
    val campus: String? = null
) {
    val timeRange: String
        get() = "${startTime.format(HH_MM)}–${endTime.format(HH_MM)}"

    companion object {
        val HH_MM: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    }
}

/** Where a given occurrence came from, so the UI can label degraded data honestly. */
enum class ScheduleSource {
    /** Built-in native JSON API reusing the WebView session. */
    SIS_NATIVE,

    /** Executed through a user-configured or auto-learned API provider. */
    SIS_PROVIDER,

    /** Captured from the page's own XHR while the WebView loaded the timetable. */
    SIS_WEBVIEW_XHR,

    /** Scraped from the rendered timetable DOM. */
    SIS_WEBVIEW_DOM,

    /** Imported manually (e.g. pasted/edited by the user in a future version). */
    MANUAL;

    val isNative: Boolean get() = this == SIS_NATIVE || this == SIS_PROVIDER
    val isWebView: Boolean get() = this == SIS_WEBVIEW_XHR || this == SIS_WEBVIEW_DOM
}

/** One course as taught across the semester, i.e. a grouped view of its occurrences. */
data class Course(
    val courseCode: String?,
    val courseName: String,
    val teacher: String?,
    val teachingClass: String?,
    val occurrences: List<ClassOccurrence>
)
