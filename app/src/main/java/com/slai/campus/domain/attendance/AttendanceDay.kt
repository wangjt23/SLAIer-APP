package com.slai.campus.domain.attendance

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime

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
    return when (val resolved = resolvePeriod(date)) {
        is AttendancePeriodResult.Unavailable -> resolved.reason
        is AttendancePeriodResult.Found -> refreshPeriod(resolved.period, origin, date)
    }
}

/** A historical month selection must not leave the current attendance card stale. */
suspend fun AttendanceRepository.refreshMonthWithCurrentDay(
    month: String,
    now: LocalDateTime,
    origin: AttendanceRefreshOrigin = AttendanceRefreshOrigin.USER
): AttendanceRefreshResult {
    val selected = when (val resolved = periodForMonth(month)) {
        is AttendancePeriodResult.Unavailable -> return resolved.reason
        is AttendancePeriodResult.Found -> resolved.period
    }
    if (selected.month != month) {
        return AttendanceRefreshResult.SchemaChanged("学校考勤周期与所选月份不一致，已保留缓存")
    }
    val result = refreshPeriod(selected, origin)
    if (result is AttendanceRefreshResult.SessionExpired || result is AttendanceRefreshResult.Superseded) return result
    val date = attendanceDate(now)
    when (val current = resolvePeriod(date)) {
        is AttendancePeriodResult.Unavailable -> return combineAttendanceRefreshResults(result, current.reason)
        is AttendancePeriodResult.Found -> {
            if (date !in current.period) {
                return AttendanceRefreshResult.SchemaChanged("学校考勤周期未包含当前考勤日，已保留缓存")
            }
            if (current.period != selected) {
                return combineAttendanceRefreshResults(result, refreshPeriod(current.period, origin, date))
            }
        }
    }
    return result
}

private suspend fun AttendanceRepository.refreshPeriod(
    period: AttendancePeriod,
    origin: AttendanceRefreshOrigin,
    currentDate: LocalDate? = null
): AttendanceRefreshResult {
    if (currentDate != null && currentDate !in period) {
        return AttendanceRefreshResult.SchemaChanged("学校考勤周期未包含当前考勤日，已保留缓存")
    }
    val (from, to) = attendancePeriodPunchRange(period)
    return refreshWithPunches(period.month, from, to, origin)
}
