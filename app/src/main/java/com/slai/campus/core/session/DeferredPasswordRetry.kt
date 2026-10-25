package com.slai.campus.core.session

/** A cooldown wakeup retries only a foreground request that actually had to wait for it. */
internal class DeferredPasswordRetry {
    private var revision: Long? = null

    fun defer(status: SavedLoginStatus, now: Long) {
        if (status.saved && status.enabled && !status.paused && status.retryAfter > now) {
            revision = status.revision
        }
    }

    fun consume(status: SavedLoginStatus, now: Long): Boolean {
        if (revision == null) return false
        if (revision != status.revision || !status.saved || !status.enabled || status.paused) {
            clear()
            return false
        }
        if (!status.canAttemptAt(now)) return false
        clear()
        return true
    }

    fun clear() { revision = null }
}
