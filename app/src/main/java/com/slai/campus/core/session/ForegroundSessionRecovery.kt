package com.slai.campus.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coordinates foreground Cookie/SSO recovery. Password permission belongs inside [recover], so
 * disabling saved-password login cannot disable existing-session recovery.
 *
 * Calls are confined to the owning scope's thread. Each foreground entry, account-settings change,
 * new expiry, or explicit check is consumed once; an expired result never starts periodic polling.
 */
internal class ForegroundSessionRecovery(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val hasNetwork: () -> Boolean,
    private val recover: suspend (revision: Long) -> Unit,
    private val onNetworkUnavailable: () -> Unit = {}
) {
    private val trigger = AutomaticLoginTrigger()
    private var revision: Long? = null
    private var observedState: SessionState? = null
    private var activeJob: Job? = null
    private var wakeJob: Job? = null
    private var wakeAt: Long? = null

    fun update(foregroundAvailable: Boolean, revision: Long, state: SessionState) {
        val changedRevision = this.revision != revision
        val changedState = observedState != state
        this.revision = revision
        observedState = state
        trigger.update(foregroundAvailable, revision)

        if (!foregroundAvailable) {
            cancelWakeup()
            activeJob?.cancel()
            return
        }
        if (activeJob != null) {
            // Cancellation may need to unwind a WebView first. Its completion consumes the new
            // revision's pending event, so the replacement never overlaps the previous recovery.
            if (changedRevision) activeJob?.cancel()
            return
        }
        if (changedState && (state == SessionState.EXPIRED || state == SessionState.NEEDS_LOGIN)) {
            trigger.requestCheck(now())
        }
        runOrSchedule()
    }

    /** Tab entry, a failed attendance request, a network transition, or a password cooldown expiry. */
    fun requestCheck() {
        if (!trigger.available) return
        trigger.requestCheck(now())
        runOrSchedule()
    }

    /** Fresh authentication answers queued checks, but cannot consume a newer foreground entry. */
    fun clearRequestedCheck() {
        trigger.clearRequestedCheck()
        if (trigger.remainingDelay(now()) == null) cancelWakeup()
    }

    private fun runOrSchedule() {
        if (!scope.isActive || activeJob != null) return
        val checkedAt = now()
        val remaining = trigger.remainingDelay(checkedAt)
        if (remaining == null) {
            cancelWakeup()
            return
        }
        if (remaining > 0L) {
            scheduleWakeup(checkedAt, remaining)
            return
        }
        cancelWakeup()
        // Do not consume pending work while offline. A later network event can recover it.
        if (!hasNetwork()) {
            onNetworkUnavailable()
            return
        }
        if (!trigger.claim(checkedAt)) return
        val recoveringRevision = checkNotNull(revision)
        val job = scope.launch(start = CoroutineStart.LAZY) { recover(recoveringRevision) }
        activeJob = job
        // A job can be cancelled before its body starts, in which case a body-level finally never
        // runs. Completion always releases the claim and preserves any event queued meanwhile.
        job.invokeOnCompletion {
            if (activeJob === job) {
                activeJob = null
                trigger.finish()
                runOrSchedule()
            }
        }
        job.start()
    }

    private fun scheduleWakeup(checkedAt: Long, remaining: Long) {
        val deadline = checkedAt + remaining
        if (wakeJob?.isActive == true && wakeAt == deadline) return
        cancelWakeup()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(remaining)
            // Clear before driving the next attempt so it cannot cancel its own coroutine.
            wakeJob = null
            wakeAt = null
            runOrSchedule()
        }
        wakeJob = job
        wakeAt = deadline
        job.invokeOnCompletion {
            if (wakeJob === job) {
                wakeJob = null
                wakeAt = null
            }
        }
        job.start()
    }

    private fun cancelWakeup() {
        val old = wakeJob
        wakeJob = null
        wakeAt = null
        old?.cancel()
    }
}
