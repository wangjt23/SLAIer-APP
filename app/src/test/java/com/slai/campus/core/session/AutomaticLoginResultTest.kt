package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.SchoolSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticLoginResultTest {
    @Test fun `whole flow deadline cancels both session wait and login work`() = runTest {
        var waiterCancelled = false
        var loginCancelled = false
        val result = awaitAutomaticLoginResult(
            awaitFreshAuthentication = { try { awaitCancellation() } finally { waiterCancelled = true } },
            hasFreshAuthentication = { false },
            signIn = { try { awaitCancellation() } finally { loginCancelled = true } },
            timeoutMs = 2_000
        )
        assertThat(result).isFalse()
        assertThat(waiterCancelled).isTrue()
        assertThat(loginCancelled).isTrue()
        assertThat(testScheduler.currentTime).isEqualTo(2_000)
    }

    @Test fun `leaving the app cancels the whole flow rather than waiting for its deadline`() = runTest {
        var loginCancelled = false
        val job = async {
            awaitAutomaticLoginResult(
                { awaitCancellation() }, { false },
                { try { awaitCancellation() } finally { loginCancelled = true } }
            )
        }
        runCurrent()
        job.cancelAndJoin()
        assertThat(job.isCancelled).isTrue()
        assertThat(loginCancelled).isTrue()
        assertThat(testScheduler.currentTime).isEqualTo(0)
    }

    @Test fun `fresh attendance success closes a stuck login and cancels its page`() = runTest {
        val evidence = AuthenticationEvidence()
        val before = evidence.version(SchoolSystem.STU)
        var destroyed = false
        launch { delay(300); evidence.confirmed(SchoolSystem.STU) }
        val result = awaitAutomaticLoginResult(
            { evidence.awaitAfter(SchoolSystem.STU, before) },
            { evidence.version(SchoolSystem.STU) > before },
            { try { awaitCancellation() } finally { destroyed = true } }
        )
        assertThat(result).isTrue()
        assertThat(destroyed).isTrue()
        assertThat(testScheduler.currentTime).isEqualTo(300)
    }

    @Test fun `old authentication and another system do not finish this attempt`() = runTest {
        val evidence = AuthenticationEvidence()
        evidence.confirmed(SchoolSystem.STU)
        val before = evidence.version(SchoolSystem.STU)
        val job = async {
            awaitAutomaticLoginResult(
                { evidence.awaitAfter(SchoolSystem.STU, before) },
                { evidence.version(SchoolSystem.STU) > before },
                { delay(1_000); false }
            )
        }
        runCurrent()
        evidence.confirmed(SchoolSystem.SIS)
        advanceTimeBy(500)
        assertThat(job.isCompleted).isFalse()
        assertThat(job.await()).isFalse()
    }

    @Test fun `a confirmation at the timeout boundary wins over a false failure`() = runTest {
        var fresh = false
        assertThat(awaitAutomaticLoginResult(
            { awaitCancellation() }, { fresh }, { fresh = true; false }
        )).isTrue()
    }
}
