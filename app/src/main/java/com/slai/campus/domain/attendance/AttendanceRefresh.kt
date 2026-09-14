package com.slai.campus.domain.attendance

import java.time.LocalDate

/** A successful summary must never hide failed punch records (the source of today's duration). */
suspend fun AttendanceRepository.refreshWithPunches(
    month: String,
    from: LocalDate,
    to: LocalDate
): AttendanceRefreshResult {
    val summary = refresh(month)
    if (summary is AttendanceRefreshResult.SessionExpired) return summary
    val punches = refreshPunches(from, to)
    return when {
        punches is AttendanceRefreshResult.SessionExpired -> punches
        !summary.isSuccess -> summary
        !punches.isSuccess -> punches
        else -> summary
    }
}

/** Include boundary context for overnight stays, even at a month boundary. */
fun monthPunchRange(month: java.time.YearMonth): Pair<LocalDate, LocalDate> =
    month.atDay(1).minusDays(8) to month.plusMonths(1).atDay(1)
