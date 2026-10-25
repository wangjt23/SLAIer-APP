package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutomaticLoginTriggerTest {
    @Test fun `expiry inside the check interval remains queued until one delayed recovery`() {
        val trigger = AutomaticLoginTrigger()
        trigger.update(true, 1)
        assertThat(trigger.claim(0)).isTrue()
        trigger.finish()

        // An attendance request discovers expiry ten seconds after a foreground check.
        assertThat(trigger.requestCheck(10_000)).isEqualTo(50_000)
        assertThat(trigger.claim(10_000)).isFalse()
        assertThat(trigger.remainingDelay(10_000)).isEqualTo(50_000)
        assertThat(trigger.remainingDelay(59_999)).isEqualTo(1)
        assertThat(trigger.claim(60_000)).isTrue()
        trigger.finish()

        // Failure leaves the UI expired, but does not manufacture a new recovery event.
        assertThat(trigger.remainingDelay(60_000)).isNull()
        assertThat(trigger.claim(120_000)).isFalse()
        assertThat(trigger.remainingDelay(86_400_000)).isNull()
    }

    @Test fun `requests during a running recovery merge into one deferred check`() {
        val trigger = AutomaticLoginTrigger()
        trigger.update(true, 1)
        assertThat(trigger.claim(0)).isTrue()
        trigger.requestCheck(10_000)
        trigger.requestCheck(20_000)
        assertThat(trigger.remainingDelay(20_000)).isNull()
        trigger.finish()
        assertThat(trigger.remainingDelay(20_000)).isEqualTo(40_000)
        assertThat(trigger.claim(60_000)).isTrue()
        trigger.finish()
        assertThat(trigger.remainingDelay(120_000)).isNull()
    }

    @Test fun `background cancels deferred check and next foreground is an immediate new event`() {
        val trigger = AutomaticLoginTrigger()
        trigger.update(true, 1)
        assertThat(trigger.claim(0)).isTrue()
        trigger.finish()
        trigger.requestCheck(10_000)
        trigger.update(false, 1)
        assertThat(trigger.remainingDelay(60_000)).isNull()
        assertThat(trigger.claim(60_000)).isFalse()
        trigger.update(true, 1)
        assertThat(trigger.remainingDelay(60_001)).isEqualTo(0)
        assertThat(trigger.claim(60_001)).isTrue()
    }

    @Test fun `fresh success clears queued recovery but preserves a new foreground event`() {
        val trigger = AutomaticLoginTrigger()
        trigger.update(true, 1)
        assertThat(trigger.claim(0)).isTrue()
        trigger.requestCheck(10_000)
        trigger.clearRequestedCheck()
        trigger.finish()
        assertThat(trigger.claim(60_000)).isFalse()
        assertThat(trigger.remainingDelay(60_000)).isNull()

        trigger.update(false, 1)
        trigger.update(true, 1)
        trigger.requestCheck(60_001)
        trigger.clearRequestedCheck()
        assertThat(trigger.claim(60_001)).isTrue()
    }

    @Test fun `network recovery after error queues a bounded read only check`() {
        val trigger = AutomaticLoginTrigger()
        trigger.update(true, 1)
        assertThat(trigger.claim(0)).isTrue()
        trigger.finish()
        assertThat(trigger.requestCheck(10_000)).isEqualTo(50_000)
        assertThat(trigger.claim(10_000)).isFalse()
        assertThat(trigger.claim(59_999)).isFalse()
        assertThat(trigger.claim(60_000)).isTrue()
        trigger.finish()
        assertThat(trigger.claim(120_000)).isFalse()
    }

    @Test fun `network request during an active probe stays pending until completion`() {
        val trigger = AutomaticLoginTrigger()
        trigger.update(true, 1)
        assertThat(trigger.claim(0)).isTrue()
        assertThat(trigger.requestCheck(60_000)).isEqualTo(0)
        assertThat(trigger.claim(60_000)).isFalse()
        trigger.finish()
        assertThat(trigger.claim(60_000)).isTrue()
    }

    @Test fun `duplicate recovery requests share the same minimum check deadline`() {
        val trigger = AutomaticLoginTrigger()
        trigger.update(true, 1)
        assertThat(trigger.claim(0)).isTrue()
        trigger.finish()
        assertThat(trigger.requestCheck(10_000)).isEqualTo(50_000)
        assertThat(trigger.requestCheck(20_000)).isEqualTo(40_000)
        assertThat(trigger.claim(60_000)).isTrue()
        trigger.finish()
        assertThat(trigger.claim(120_000)).isFalse()
    }

    @Test fun `next foreground checks live session even if cached state says authenticated`() {
        val trigger = AutomaticLoginTrigger()
        trigger.update(true, 1)
        assertThat(trigger.claim(100)).isTrue()
        trigger.finish()
        assertThat(trigger.claim(200)).isFalse()
        trigger.update(false, 1)
        trigger.update(true, 1)
        assertThat(trigger.claim(86_400_000)).isTrue()
    }

    @Test fun `failed attempt does not suppress the next day with the same saved account`() {
        val trigger = AutomaticLoginTrigger()
        trigger.update(true, 1)
        assertThat(trigger.claim(100)).isTrue()
        trigger.finish()
        assertThat(trigger.claim(200)).isFalse()
        trigger.update(false, 1)
        trigger.update(true, 1)
        assertThat(trigger.claim(86_400_000)).isTrue()
    }

    @Test fun `foreground or settings event during cancellation is not lost`() {
        for (changeRevision in listOf(false, true)) {
            val trigger = AutomaticLoginTrigger()
            trigger.update(true, 1)
            assertThat(trigger.claim(0)).isTrue()
            if (!changeRevision) trigger.update(false, 1)
            trigger.update(true, if (changeRevision) 2 else 1)
            assertThat(trigger.claim(100)).isFalse()
            trigger.finish()
            assertThat(trigger.claim(100)).isTrue()
        }
    }

    @Test fun `pause and recomposition cannot start another concurrent flow`() {
        val trigger = AutomaticLoginTrigger()
        trigger.update(true, 1)
        assertThat(trigger.claim(0)).isTrue()
        trigger.update(true, 1)
        assertThat(trigger.claim(0)).isFalse()
        trigger.update(false, 1)
        trigger.finish()
        assertThat(trigger.claim(100_000)).isFalse()
    }

    @Test fun `interrupted password submission cools down instead of permanently pausing`() {
        val status = SavedLoginStatus(saved = true, enabled = true, retryAfter = AUTOMATIC_LOGIN_RETRY_DELAY_MS)
        assertThat(status.canAttemptAt(0)).isFalse()
        assertThat(status.canAttemptAt(AUTOMATIC_LOGIN_RETRY_DELAY_MS - 1)).isFalse()
        assertThat(status.canAttemptAt(AUTOMATIC_LOGIN_RETRY_DELAY_MS)).isTrue()
        assertThat(status.canAttemptAt(86_400_000)).isTrue()
        assertThat(status.copy(paused = true).canAttemptAt(86_400_000)).isFalse()
        assertThat(status.copy(enabled = false).canAttemptAt(86_400_000)).isFalse()
    }
}
