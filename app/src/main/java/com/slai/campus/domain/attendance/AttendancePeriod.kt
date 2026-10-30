package com.slai.campus.domain.attendance

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** A school's statistical month, whose complete weeks may start in the preceding calendar month. */
data class AttendancePeriod(
    val month: String,
    val start: LocalDate,
    val endInclusive: LocalDate
) {
    operator fun contains(date: LocalDate): Boolean = date in start..endInclusive
}

/**
 * Accept only the school's complete, consecutive weeks. Daily rows cannot establish a period:
 * future weeks can have no rows and a day without swipes is still part of the school's month.
 */
fun attendancePeriod(month: String, ranges: List<Pair<LocalDate, LocalDate>>): AttendancePeriod? {
    if (!Regex("\\d{4}-\\d{2}").matches(month) || ranges.size !in 1..6) return null
    val calendar = runCatching { YearMonth.parse(month) }.getOrNull() ?: return null
    val ordered = ranges.sortedBy { it.first }
    // This only rejects a wrong month/year or an implausibly long response. It does not infer
    // where the school starts its period or which month owns a boundary week.
    if (ordered.first().first > calendar.atEndOfMonth() || ordered.last().second < calendar.atDay(1)) return null
    if (ordered.first().first < calendar.minusMonths(1).atDay(1) ||
        ordered.last().second > calendar.plusMonths(1).atEndOfMonth()) return null
    if (ordered.any { (start, end) ->
            start.dayOfWeek != DayOfWeek.MONDAY || end != runCatching { start.plusDays(6) }.getOrNull()
        }) return null
    if (ordered.zipWithNext().any { (previous, next) -> next.first != previous.second.plusDays(1) }) return null
    return AttendancePeriod(month, ordered.first().first, ordered.last().second)
}

/** A date belongs to a period only when the verified school ranges give one unambiguous answer. */
fun currentAttendancePeriod(date: LocalDate, periods: List<AttendancePeriod>): AttendancePeriod? =
    periods.distinct().filter { date in it }.singleOrNull()

sealed interface AttendancePeriodResult {
    data class Found(val period: AttendancePeriod) : AttendancePeriodResult
    data class Unavailable(val reason: AttendanceRefreshResult) : AttendancePeriodResult
}

/** Include an earlier entry and the next day's exit while fetching all of the selected period. */
fun attendancePeriodPunchRange(period: AttendancePeriod): Pair<LocalDate, LocalDate> =
    period.start.minusDays(1) to period.endInclusive.plusDays(1)
