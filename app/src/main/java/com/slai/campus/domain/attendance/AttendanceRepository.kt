package com.slai.campus.domain.attendance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

interface AttendanceRepository {

    /** Final summary-and-punch results, shared across foreground recovery and both screens. */
    val refreshUpdates: StateFlow<Map<AttendanceRefreshScope, AttendanceRefreshUpdate>>
        get() = EMPTY_REFRESH_UPDATES

    /** Cached attendance for [date]. Reads Room only. */
    fun observeDay(date: LocalDate): Flow<AttendanceRecord?>

    /** Cached attendance for a whole month, ordered by date. Reads Room only. */
    fun observeMonth(month: String): Flow<AttendanceMonth>

    /** Verified school periods for this account. Reads cache only, including before first refresh. */
    fun observePeriods(): Flow<List<AttendancePeriod>>

    /** Resolve the school month containing [date], consulting the school when cache is insufficient. */
    suspend fun resolvePeriod(date: LocalDate): AttendancePeriodResult

    /** Read the school's full week range for a particular statistical month. */
    suspend fun periodForMonth(month: String): AttendancePeriodResult

    /** Fetches (or refreshes) one month from the school. */
    suspend fun refresh(month: String): AttendanceRefreshResult

    /** Cached punch records, including adjacent dates needed to pair overnight stays. */
    fun observePunches(from: LocalDate, to: LocalDate): Flow<List<AttendancePunch>>

    /** Fetches punch records for [from]..[to]. */
    suspend fun refreshPunches(from: LocalDate, to: LocalDate): AttendanceRefreshResult

    /** Publish only the complete result: a summary alone cannot validate punch records. */
    suspend fun refreshWithPunches(month: String, from: LocalDate, to: LocalDate,
                                  origin: AttendanceRefreshOrigin = AttendanceRefreshOrigin.USER): AttendanceRefreshResult =
        refreshSummaryAndPunches(month, from, to)
}

private val EMPTY_REFRESH_UPDATES = MutableStateFlow<Map<AttendanceRefreshScope, AttendanceRefreshUpdate>>(emptyMap())
