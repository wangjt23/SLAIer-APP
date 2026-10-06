package com.slai.campus.core.session

/** One recovery at a time. Foreground entries are remembered even while cancellation is finishing. */
internal class AutomaticLoginTrigger {
    var available = false
        private set
    private var revision: Long? = null
    private var pending = false
    private var active = false
    private var nextCheckAt = 0L

    fun update(available: Boolean, revision: Long) {
        if (available && (!this.available || this.revision != revision)) pending = true
        this.available = available
        this.revision = revision
    }

    fun claim(state: SessionState, now: Long): Boolean {
        if (!available || active) return false
        if (!pending && (now < nextCheckAt || state !in setOf(SessionState.EXPIRED, SessionState.NEEDS_LOGIN))) return false
        pending = false
        active = true
        nextCheckAt = now + 60_000
        return true
    }

    fun finish() { active = false }
}
