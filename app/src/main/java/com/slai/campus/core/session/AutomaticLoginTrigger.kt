package com.slai.campus.core.session

/** One event-driven recovery at a time; a failed result does not schedule another recovery. */
internal class AutomaticLoginTrigger {
    var available = false
        private set
    private var revision: Long? = null
    private var pending = false
    private var requested = false
    private var active = false
    private var nextCheckAt = 0L

    fun update(available: Boolean, revision: Long) {
        if (available && (!this.available || this.revision != revision)) pending = true
        if (!available) {
            pending = false
            requested = false
        }
        this.available = available
        this.revision = revision
    }

    /**
     * A newly expired session, network transition, or elapsed credential cooldown asks for a check.
     * Return the remaining check delay so its caller can arrange one wakeup, without polling.
     * Foreground and account changes keep their existing immediate-check behavior.
     */
    fun requestCheck(now: Long): Long {
        requested = true
        return if (pending) 0 else (nextCheckAt - now).coerceAtLeast(0)
    }

    /** Fresh authentication already answers an explicitly queued recovery probe. */
    fun clearRequestedCheck() { requested = false }

    /**
     * The caller schedules one delayed wakeup for this deadline. No pending event means no timer:
     * keeping EXPIRED on screen is not itself a reason to retry every minute.
     */
    fun remainingDelay(now: Long): Long? {
        if (!available || active || (!pending && !requested)) return null
        return if (pending) 0 else (nextCheckAt - now).coerceAtLeast(0)
    }

    fun claim(now: Long): Boolean {
        if (remainingDelay(now) != 0L) return false
        pending = false
        requested = false
        active = true
        nextCheckAt = now + 60_000
        return true
    }

    fun finish() { active = false }
}
