package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeferredPasswordRetryTest {
    private val cooling = SavedLoginStatus(saved = true, enabled = true, revision = 1, retryAfter = 300_000)

    @Test fun `a failed password submission alone cannot create periodic cooldown retries`() {
        val retry = DeferredPasswordRetry()
        assertThat(retry.consume(cooling, 300_000)).isFalse()
        assertThat(retry.consume(cooling.copy(retryAfter = 600_000), 600_000)).isFalse()
    }

    @Test fun `foreground interrupted attempt waits its deadline and gets one recovery`() {
        val retry = DeferredPasswordRetry()
        retry.defer(cooling, 100_000)
        assertThat(retry.consume(cooling, 299_999)).isFalse()
        assertThat(retry.consume(cooling, 300_000)).isTrue()
        assertThat(retry.consume(cooling.copy(retryAfter = 600_000), 600_000)).isFalse()
    }

    @Test fun `an actual password attempt or cookie success clears its deferred request`() {
        val retry = DeferredPasswordRetry()
        retry.defer(cooling, 100_000)
        retry.clear()
        assertThat(retry.consume(cooling, 300_000)).isFalse()
    }

    @Test fun `a deferred request cannot authorize changed disabled paused or deleted credentials`() {
        for (changed in listOf(cooling.copy(revision = 2), cooling.copy(enabled = false),
            cooling.copy(paused = true), SavedLoginStatus())) {
            val retry = DeferredPasswordRetry()
            retry.defer(cooling, 0)
            assertThat(retry.consume(changed, 300_000)).isFalse()
            assertThat(retry.consume(cooling, 300_001)).isFalse()
        }
    }
}
