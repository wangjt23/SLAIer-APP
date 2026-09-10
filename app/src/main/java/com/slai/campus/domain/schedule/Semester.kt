package com.slai.campus.domain.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Semester identity plus the calendar anchor required to turn "第 N 周" into concrete dates.
 *
 * The source system (ZFSoft) expresses a course as "weekday 3, periods 5-6, weeks 1-16 (odd)", which
 * is not a date. [firstWeekMonday] is what makes the conversion possible. It comes from, in order of
 * preference:
 *  1. a date the timetable page itself rendered,
 *  2. a value the user confirmed in Settings,
 *  3. an explicit heuristic (see `SemesterCalendarResolver`) clearly labelled as an estimate.
 */
data class Semester(
    /** Source-system academic year code, e.g. ZFSoft `xnm=2026`. */
    val academicYear: String? = null,
    /** Source-system term code, e.g. ZFSoft `xqm=3` (first term) / `12` (second term). */
    val termCode: String? = null,
    val displayName: String? = null,
    /** Monday of teaching week 1. Null when the source system did not disclose it. */
    val firstWeekMonday: LocalDate? = null
) {
    val isAnchored: Boolean get() = firstWeekMonday != null

    /** Monday of the week containing [date]. */
    fun mondayOf(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** 1-based teaching week containing [date], or null when unanchored. */
    fun weekIndexOf(date: LocalDate): Int? {
        val anchor = firstWeekMonday ?: return null
        return (ChronoUnit.WEEKS.between(anchor, mondayOf(date)) + 1).toInt()
    }

    /** All dates of teaching week [weekIndex] (Monday..Sunday), or empty when unanchored. */
    fun datesOfWeek(weekIndex: Int): List<LocalDate> {
        val anchor = firstWeekMonday ?: return emptyList()
        val monday = anchor.plusWeeks((weekIndex - 1).toLong())
        return (0L..6L).map { monday.plusDays(it) }
    }

    fun withAnchor(monday: LocalDate?): Semester = copy(firstWeekMonday = monday)

    companion object {
        fun termDisplayName(termCode: String?): String? = when (termCode?.trim()) {
            "3" -> "第一学期"
            "12" -> "第二学期"
            "16" -> "第三学期"
            else -> null
        }
    }
}
