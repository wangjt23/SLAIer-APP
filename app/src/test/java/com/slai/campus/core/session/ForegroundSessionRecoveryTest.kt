package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSessionRecoveryTest {
    @Test fun `a user expiry after a successful check survives until one follow up`() = runTest {
        val refreshFinished = CompletableDeferred<Unit>()
        var attempts = 0
        lateinit var recovery: ForegroundSessionRecovery
        recovery = ForegroundSessionRecovery(backgroundScope, { testScheduler.currentTime }, { true }, {
            attempts++
            recovery.update(true, 1, SessionState.AUTHENTICATED)
            if (attempts == 1) refreshFinished.await()
        })
        recovery.update(true, 1, SessionState.AUTHENTICATED)
        runCurrent()
        advanceTimeBy(10_000)
        // A different, user-requested refresh rejects the session after the successful probe.
        recovery.update(true, 1, SessionState.EXPIRED)
        recovery.requestCheck()
        refreshFinished.complete(Unit)
        runCurrent()
        advanceTimeBy(50_000)
        runCurrent()
        assertThat(attempts).isEqualTo(2)
        advanceTimeBy(600_000)
        runCurrent()
        assertThat(attempts).isEqualTo(2)
    }

    @Test fun `successful month probe and expired punches cannot restart their own recovery`() = runTest {
        var attempts = 0
        lateinit var recovery: ForegroundSessionRecovery
        recovery = ForegroundSessionRecovery(backgroundScope, { testScheduler.currentTime }, { true }, {
            attempts++
            recovery.update(true, 1, SessionState.AUTHENTICATED)
            // Main awaits its SESSION_RECOVERY refresh inside this active flow and synchronizes
            // the final state before asynchronous UI effects can see the punch rejection.
            recovery.update(true, 1, SessionState.EXPIRED)
        })
        recovery.update(true, 1, SessionState.EXPIRED)
        runCurrent()
        advanceTimeBy(600_000)
        recovery.update(true, 1, SessionState.EXPIRED)
        runCurrent()
        assertThat(attempts).isEqualTo(1)
    }

    @Test fun `returning after a long background recovers without any password setting gate`() = runTest {
        val attempts = mutableListOf<Long>()
        val recovery = ForegroundSessionRecovery(backgroundScope, { testScheduler.currentTime }, { true },
            { attempts += it })
        recovery.update(true, 0, SessionState.AUTHENTICATED)
        runCurrent()
        recovery.update(false, 0, SessionState.AUTHENTICATED)
        advanceTimeBy(86_400_000)
        recovery.update(true, 0, SessionState.EXPIRED)
        runCurrent()
        assertThat(attempts).containsExactly(0L, 0L)
        advanceTimeBy(86_400_000)
        runCurrent()
        assertThat(attempts).hasSize(2)
    }

    @Test fun `expiry discovered inside the interval automatically recovers at the deadline`() = runTest {
        val attempts = mutableListOf<Long>()
        val recovery = ForegroundSessionRecovery(backgroundScope, { testScheduler.currentTime }, { true },
            { attempts += testScheduler.currentTime })
        recovery.update(true, 1, SessionState.AUTHENTICATED)
        runCurrent()
        advanceTimeBy(10_000)
        recovery.update(true, 1, SessionState.EXPIRED)
        runCurrent()
        advanceTimeBy(49_999)
        runCurrent()
        assertThat(attempts).containsExactly(0L)
        advanceTimeBy(1)
        runCurrent()
        assertThat(attempts).containsExactly(0L, 60_000L).inOrder()
    }

    @Test fun `failed recovery and repeated expired updates do not turn into polling`() = runTest {
        var attempts = 0
        val recovery = ForegroundSessionRecovery(backgroundScope, { testScheduler.currentTime }, { true },
            { attempts++ })
        recovery.update(true, 1, SessionState.EXPIRED)
        runCurrent()
        repeat(5) {
            advanceTimeBy(60_000)
            recovery.update(true, 1, SessionState.EXPIRED)
            runCurrent()
        }
        assertThat(attempts).isEqualTo(1)
    }

    @Test fun `background cancels the delayed check and foreground makes a new immediate attempt`() = runTest {
        val attempts = mutableListOf<Long>()
        val recovery = ForegroundSessionRecovery(backgroundScope, { testScheduler.currentTime }, { true },
            { attempts += testScheduler.currentTime })
        recovery.update(true, 1, SessionState.AUTHENTICATED)
        runCurrent()
        advanceTimeBy(10_000)
        recovery.requestCheck()
        recovery.update(false, 1, SessionState.EXPIRED)
        advanceTimeBy(70_000)
        runCurrent()
        assertThat(attempts).containsExactly(0L)
        recovery.update(true, 1, SessionState.EXPIRED)
        runCurrent()
        assertThat(attempts).containsExactly(0L, 80_000L).inOrder()
    }

    @Test fun `background cancels an active recovery and drops requests queued in that foreground`() = runTest {
        var started = 0
        var cancelled = 0
        val recovery = ForegroundSessionRecovery(backgroundScope, { testScheduler.currentTime }, { true }, {
            started++
            try { awaitCancellation() } finally { cancelled++ }
        })
        recovery.update(true, 1, SessionState.AUTHENTICATED)
        runCurrent()
        recovery.requestCheck()
        recovery.update(false, 1, SessionState.EXPIRED)
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertThat(started).isEqualTo(1)
        assertThat(cancelled).isEqualTo(1)
        recovery.update(true, 1, SessionState.EXPIRED)
        runCurrent()
        assertThat(started).isEqualTo(2)
    }

    @Test fun `revision change waits for old recovery cancellation before starting its replacement`() = runTest {
        val revisions = mutableListOf<Long>()
        val oldUnwinding = CompletableDeferred<Unit>()
        val allowOldToFinish = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        val recovery = ForegroundSessionRecovery(backgroundScope, { testScheduler.currentTime }, { true }, {
            revisions += it
            active++
            maxActive = maxOf(maxActive, active)
            try {
                if (it == 1L) awaitCancellation()
            } finally {
                if (it == 1L) withContext(NonCancellable) {
                    oldUnwinding.complete(Unit)
                    allowOldToFinish.await()
                }
                active--
            }
        })
        recovery.update(true, 1, SessionState.AUTHENTICATED)
        runCurrent()
        recovery.update(true, 2, SessionState.AUTHENTICATED)
        runCurrent()
        assertThat(oldUnwinding.isCompleted).isTrue()
        assertThat(revisions).containsExactly(1L)
        allowOldToFinish.complete(Unit)
        runCurrent()
        assertThat(revisions).containsExactly(1L, 2L).inOrder()
        assertThat(maxActive).isEqualTo(1)
    }

    @Test fun `several network events while running merge into one delayed follow up`() = runTest {
        val first = CompletableDeferred<Unit>()
        val attempts = mutableListOf<Long>()
        val recovery = ForegroundSessionRecovery(backgroundScope, { testScheduler.currentTime }, { true }, {
            attempts += testScheduler.currentTime
            if (attempts.size == 1) first.await()
        })
        recovery.update(true, 1, SessionState.AUTHENTICATED)
        runCurrent()
        advanceTimeBy(10_000)
        repeat(10) { recovery.requestCheck() }
        first.complete(Unit)
        runCurrent()
        advanceTimeBy(10_000)
        repeat(10) { recovery.requestCheck() }
        advanceTimeBy(40_000)
        runCurrent()
        assertThat(attempts).containsExactly(0L, 60_000L).inOrder()
        advanceTimeBy(120_000)
        runCurrent()
        assertThat(attempts).hasSize(2)
    }

    @Test fun `opening attendance again can retry an unchanged expired state once`() = runTest {
        var attempts = 0
        val recovery = ForegroundSessionRecovery(backgroundScope, { testScheduler.currentTime }, { true },
            { attempts++ })
        recovery.update(true, 1, SessionState.EXPIRED)
        runCurrent()
        advanceTimeBy(120_000)
        recovery.requestCheck()
        runCurrent()
        assertThat(attempts).isEqualTo(2)
        advanceTimeBy(120_000)
        runCurrent()
        assertThat(attempts).isEqualTo(2)
    }

    @Test fun `a running recovery discovering expiry does not queue itself again`() = runTest {
        var attempts = 0
        lateinit var recovery: ForegroundSessionRecovery
        recovery = ForegroundSessionRecovery(backgroundScope, { testScheduler.currentTime }, { true }, {
            attempts++
            recovery.update(true, 1, SessionState.EXPIRED)
        })
        recovery.update(true, 1, SessionState.AUTHENTICATED)
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertThat(attempts).isEqualTo(1)
    }

    @Test fun `offline foreground keeps pending work until a real network event`() = runTest {
        var online = false
        var attempts = 0
        var unavailable = 0
        val recovery = ForegroundSessionRecovery(backgroundScope, { testScheduler.currentTime }, { online },
            { attempts++ }, { unavailable++ })
        recovery.update(true, 1, SessionState.AUTHENTICATED)
        runCurrent()
        assertThat(unavailable).isEqualTo(1)
        advanceTimeBy(120_000)
        runCurrent()
        assertThat(attempts).isEqualTo(0)
        online = true
        recovery.requestCheck()
        runCurrent()
        assertThat(attempts).isEqualTo(1)
    }

    @Test fun `fresh authentication clears a queued network check without losing a new foreground`() = runTest {
        val first = CompletableDeferred<Unit>()
        var attempts = 0
        val recovery = ForegroundSessionRecovery(backgroundScope, { testScheduler.currentTime }, { true }, {
            attempts++
            if (attempts == 1) first.await()
        })
        recovery.update(true, 1, SessionState.AUTHENTICATED)
        runCurrent()
        recovery.requestCheck()
        recovery.clearRequestedCheck()
        first.complete(Unit)
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertThat(attempts).isEqualTo(1)
        recovery.update(false, 1, SessionState.AUTHENTICATED)
        recovery.update(true, 1, SessionState.AUTHENTICATED)
        recovery.clearRequestedCheck()
        runCurrent()
        assertThat(attempts).isEqualTo(2)
    }

    @Test fun `cancelling before a recovery coroutine starts cannot leave the coordinator stuck`() = runTest {
        val attempts = mutableListOf<Long>()
        val recovery = ForegroundSessionRecovery(backgroundScope, { testScheduler.currentTime }, { true },
            { attempts += it })
        recovery.update(true, 1, SessionState.AUTHENTICATED)
        recovery.update(false, 1, SessionState.AUTHENTICATED)
        recovery.update(true, 2, SessionState.EXPIRED)
        runCurrent()
        assertThat(attempts).containsExactly(2L)
    }
}
