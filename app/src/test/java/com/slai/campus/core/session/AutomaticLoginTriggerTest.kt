package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutomaticLoginTriggerTest {
    @Test fun `next foreground checks live session even if cached state says authenticated`() {
        val trigger = AutomaticLoginTrigger()
        trigger.update(true, 1)
        assertThat(trigger.claim(SessionState.AUTHENTICATED, 100)).isTrue()
        trigger.finish()
        assertThat(trigger.claim(SessionState.AUTHENTICATED, 200)).isFalse()
        trigger.update(false, 1)
        trigger.update(true, 1)
        assertThat(trigger.claim(SessionState.AUTHENTICATED, 86_400_000)).isTrue()
    }

    @Test fun `failed attempt does not suppress the next day with the same saved account`() {
        val trigger = AutomaticLoginTrigger()
        trigger.update(true, 1)
        assertThat(trigger.claim(SessionState.EXPIRED, 100)).isTrue()
        trigger.finish()
        assertThat(trigger.claim(SessionState.EXPIRED, 200)).isFalse()
        trigger.update(false, 1)
        trigger.update(true, 1)
        assertThat(trigger.claim(SessionState.EXPIRED, 86_400_000)).isTrue()
    }

    @Test fun `foreground or settings event during cancellation is not lost`() {
        for (changeRevision in listOf(false, true)) {
            val trigger = AutomaticLoginTrigger()
            trigger.update(true, 1)
            assertThat(trigger.claim(SessionState.EXPIRED, 0)).isTrue()
            if (!changeRevision) trigger.update(false, 1)
            trigger.update(true, if (changeRevision) 2 else 1)
            assertThat(trigger.claim(SessionState.EXPIRED, 100)).isFalse()
            trigger.finish()
            assertThat(trigger.claim(SessionState.EXPIRED, 100)).isTrue()
        }
    }

    @Test fun `pause and recomposition cannot start another concurrent flow`() {
        val trigger = AutomaticLoginTrigger()
        trigger.update(true, 1)
        assertThat(trigger.claim(SessionState.EXPIRED, 0)).isTrue()
        trigger.update(true, 1)
        assertThat(trigger.claim(SessionState.EXPIRED, 0)).isFalse()
        trigger.update(false, 1)
        trigger.finish()
        assertThat(trigger.claim(SessionState.EXPIRED, 100_000)).isFalse()
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
