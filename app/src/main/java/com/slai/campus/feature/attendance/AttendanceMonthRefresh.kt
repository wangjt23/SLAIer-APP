package com.slai.campus.feature.attendance

import com.slai.campus.domain.attendance.AttendanceRefreshResult
import com.slai.campus.domain.attendance.attendanceDate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.LocalDateTime

/** A month can roll over while the school request is in flight; only return its current result. */
internal suspend fun refreshSelectedAttendanceMonth(
    selectedMonth: () -> String,
    now: () -> LocalDateTime,
    refreshMonth: suspend (String, LocalDateTime) -> AttendanceRefreshResult,
    updateClock: (LocalDateTime) -> Unit
): AttendanceRefreshResult {
    while (true) {
        currentCoroutineContext().ensureActive()
        val requestedMonth = selectedMonth()
        val startedAt = now()
        val requestedDate = attendanceDate(startedAt)
        val result = refreshMonth(requestedMonth, startedAt)
        currentCoroutineContext().ensureActive()
        val finishedAt = now()
        updateClock(finishedAt)
        // Expiration is a system-wide result; another month cannot recover a missing session.
        if (result is AttendanceRefreshResult.SessionExpired ||
            (requestedMonth == selectedMonth() && requestedDate == attendanceDate(finishedAt))) return result
        // The previous request still refreshed its cache. Fetch the newly selected month before
        // publishing a result, keeping the UI in its refreshing state between the two requests.
        // A fixed historical selection also needs fresh current-day data after a day rollover.
    }
}
