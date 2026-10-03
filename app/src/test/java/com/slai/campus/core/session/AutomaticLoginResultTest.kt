package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.SchoolSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticLoginResultTest {
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
