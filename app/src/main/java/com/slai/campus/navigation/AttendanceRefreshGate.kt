package com.slai.campus.navigation

import java.time.LocalDate

/** Avoid refetching on every short foreground/tab transition; a new login always refreshes. */
internal class AttendanceRefreshGate(private val intervalMs: Long = 60_000) {
    private var accountVersion: Long? = null
    private var date: LocalDate? = null
    private var attemptedAt: Long? = null

    fun claim(accountVersion: Long, date: LocalDate, now: Long, force: Boolean): Boolean {
        val previous = attemptedAt
        if (!force && this.accountVersion == accountVersion && this.date == date &&
            previous != null && now >= previous && now - previous < intervalMs) return false
        this.accountVersion = accountVersion
        this.date = date
        attemptedAt = now
        return true
    }
}
