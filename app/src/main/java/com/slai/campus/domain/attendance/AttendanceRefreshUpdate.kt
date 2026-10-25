package com.slai.campus.domain.attendance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

data class AttendanceRefreshScope(val month: String, val from: LocalDate, val to: LocalDate) {
    fun covers(month: String, from: LocalDate, to: LocalDate): Boolean =
        this.month == month && this.from <= from && this.to >= to
}

/** Recovery refresh results update the UI but cannot trigger another recovery by themselves. */
enum class AttendanceRefreshOrigin { USER, SESSION_RECOVERY }

data class AttendanceRefreshUpdate(
    val scope: AttendanceRefreshScope,
    val accountHash: String,
    val accountVersion: Long,
    val sequence: Long,
    val result: AttendanceRefreshResult,
    val origin: AttendanceRefreshOrigin = AttendanceRefreshOrigin.USER
)

/** Bounded request metadata only; cached attendance records remain in Room. */
internal class AttendanceRefreshUpdates {
    private val mutable = MutableStateFlow<Map<AttendanceRefreshScope, AttendanceRefreshUpdate>>(emptyMap())
    val state: StateFlow<Map<AttendanceRefreshScope, AttendanceRefreshUpdate>> = mutable.asStateFlow()
    private var sequence = 0L

    @Synchronized
    fun publish(scope: AttendanceRefreshScope, accountHash: String, accountVersion: Long,
                result: AttendanceRefreshResult, origin: AttendanceRefreshOrigin = AttendanceRefreshOrigin.USER) {
        if (result is AttendanceRefreshResult.Superseded) return
        val next = mutable.value.toMutableMap()
        // Move a refreshed scope to the end before retaining the most recent contexts.
        next.remove(scope)
        next[scope] = AttendanceRefreshUpdate(scope, accountHash, accountVersion, ++sequence, result, origin)
        mutable.value = next.values.toList().takeLast(24).associateBy { it.scope }
    }
}

fun latestAttendanceRefresh(
    updates: Map<AttendanceRefreshScope, AttendanceRefreshUpdate>,
    accountHash: String?,
    accountVersion: Long,
    month: String,
    from: LocalDate,
    to: LocalDate
): AttendanceRefreshUpdate? = updates.values.asSequence()
    .filter { it.accountHash == accountHash && it.accountVersion == accountVersion && it.scope.covers(month, from, to) }
    .maxByOrNull { it.sequence }
