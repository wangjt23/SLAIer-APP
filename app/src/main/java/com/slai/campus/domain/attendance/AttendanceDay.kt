package com.slai.campus.domain.attendance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** Client display boundary, aligned with the existing open-session cutoff, not a server job time. */
fun attendanceDate(now: LocalDateTime): LocalDate =
    if (now.toLocalTime().isBefore(PunchSession.OPEN_CUTOFF)) now.toLocalDate().minusDays(1)
    else now.toLocalDate()

/** Include both the previous entry and the next calendar day's overnight exit. */
fun attendancePunchRange(date: LocalDate): Pair<LocalDate, LocalDate> =
    date.minusDays(1) to date.plusDays(1)

/** Keep the selected date attached to its cached data while a rollover changes subscriptions. */
data class CurrentAttendance(
    val date: LocalDate,
    val record: AttendanceRecord?,
    val punches: List<AttendancePunch>
)

@OptIn(ExperimentalCoroutinesApi::class)
fun AttendanceRepository.observeCurrentAttendance(clock: Flow<LocalDateTime>): Flow<CurrentAttendance> =
    clock.map(::attendanceDate).distinctUntilChanged().flatMapLatest { date ->
        val (from, to) = attendancePunchRange(date)
        combine(observeDay(date), observePunches(from, to)) { record, punches ->
            CurrentAttendance(date, record, punches)
        }
    }

/** Home refresh and post-login refresh must use the same attendance date and overnight context. */
suspend fun AttendanceRepository.refreshCurrentAttendance(
    now: LocalDateTime,
    origin: AttendanceRefreshOrigin = AttendanceRefreshOrigin.USER
): AttendanceRefreshResult {
    val date = attendanceDate(now)
    val (from, to) = attendancePunchRange(date)
    return refreshWithPunches(YearMonth.from(date).toString(), from, to, origin)
}

/** A historical month selection must not leave the current attendance card stale. */
suspend fun AttendanceRepository.refreshMonthWithCurrentDay(
    month: String,
    now: LocalDateTime,
    origin: AttendanceRefreshOrigin = AttendanceRefreshOrigin.USER
): AttendanceRefreshResult {
    val (from, to) = monthPunchRange(YearMonth.parse(month))
    val result = refreshWithPunches(month, from, to, origin)
    if (result is AttendanceRefreshResult.SessionExpired || result is AttendanceRefreshResult.Superseded) return result
    if (month != YearMonth.from(attendanceDate(now)).toString()) {
        val current = refreshCurrentAttendance(now, origin)
        if (!current.isSuccess) return current
    }
    return result
}
