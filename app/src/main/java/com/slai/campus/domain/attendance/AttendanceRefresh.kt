package com.slai.campus.domain.attendance

import java.time.LocalDate

sealed interface AttendanceRefreshResult {
    data class Success(val month: String, val days: Int) : AttendanceRefreshResult
    data object SessionExpired : AttendanceRefreshResult
    data class Offline(val cached: Boolean) : AttendanceRefreshResult

    /** Device is online, but the school host cannot be reached. */
    data class Unreachable(val reason: String) : AttendanceRefreshResult
    data class SchemaChanged(val reason: String) : AttendanceRefreshResult
    data class ServerError(val code: Int) : AttendanceRefreshResult
    data class Failed(val reason: String) : AttendanceRefreshResult

    /** A newer login/account invalidated this request; it must not change the visible result. */
    data object Superseded : AttendanceRefreshResult

    val isSuccess: Boolean get() = this is Success

    /** Cached data may be stale until the device can reach the campus network again. */
    val needsCampusNetworkHint: Boolean get() = this is Unreachable
}

/** Distinguishes an offline device from an unreachable school host. */
fun networkFailureResult(hasNetwork: Boolean, hasCache: Boolean, reason: String): AttendanceRefreshResult =
    if (hasNetwork) AttendanceRefreshResult.Unreachable(reason) else AttendanceRefreshResult.Offline(hasCache)

/** A successful summary must never hide failed punch records (the source of today's duration). */
internal suspend fun AttendanceRepository.refreshSummaryAndPunches(
    month: String,
    from: LocalDate,
    to: LocalDate
): AttendanceRefreshResult {
    val summary = refresh(month)
    if (summary is AttendanceRefreshResult.SessionExpired || summary is AttendanceRefreshResult.Superseded) return summary
    val punches = refreshPunches(from, to)
    return combineAttendanceRefreshResults(summary, punches)
}

internal fun combineAttendanceRefreshResults(
    summary: AttendanceRefreshResult,
    punches: AttendanceRefreshResult
): AttendanceRefreshResult = when {
    punches is AttendanceRefreshResult.Superseded -> punches
    punches is AttendanceRefreshResult.SessionExpired -> punches
    !summary.isSuccess -> summary
    !punches.isSuccess -> punches
    else -> summary
}

/** Include boundary context for overnight stays, even at a month boundary. */
fun monthPunchRange(month: java.time.YearMonth): Pair<LocalDate, LocalDate> =
    month.atDay(1).minusDays(8) to month.plusMonths(1).atDay(1)
