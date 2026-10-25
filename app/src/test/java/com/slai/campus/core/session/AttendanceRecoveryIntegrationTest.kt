package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.web.SilentLoginPage
import com.slai.campus.core.web.runSilentLogin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Exercise lifecycle scheduling, live verification and the hidden login flow together. */
@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceRecoveryIntegrationTest {
    private class Page(var form: String = "none") : SilentLoginPage {
        override var url: String? = "https://sts.slai.edu.cn/adfs/oauth2/authorize"
        override val failed = false
        override val generation = 1
        var passwordSubmissions = 0
        var authenticated = false
        override suspend fun inspect() = form
        override suspend fun submit(password: Boolean): String {
            if (password) { passwordSubmissions++; authenticated = true }
            return "submitted"
        }
    }

    @Test fun `long background resumes cookie recovery with missing disabled or paused passwords`() = runTest {
        for (status in listOf(SavedLoginStatus(), SavedLoginStatus(saved = true),
            SavedLoginStatus(saved = true, enabled = true, paused = true))) {
            var recoveries = 0
            val coordinator = ForegroundSessionRecovery(backgroundScope, { testScheduler.currentTime }, { true }, {
                val page = Page()
                assertThat(recoverAttendanceSession({ SessionState.EXPIRED }) {
                    recoveries++
                    runSilentLogin(page, { error("Cookie recovery must not submit a password") }, {
                        delay(100)
                        true
                    }, authorizeCredentials = { status.canAttemptAt(testScheduler.currentTime) })
                }).isTrue()
                assertThat(page.passwordSubmissions).isEqualTo(0)
            })
            coordinator.update(true, status.revision, SessionState.EXPIRED)
            advanceTimeBy(101)
            runCurrent()
            assertThat(recoveries).isEqualTo(1)
            coordinator.update(false, status.revision, SessionState.EXPIRED)
            advanceTimeBy(24 * 60 * 60_000L)
            coordinator.update(true, status.revision, SessionState.EXPIRED)
            advanceTimeBy(101)
            runCurrent()
            assertThat(recoveries).isEqualTo(2)
            coordinator.update(false, status.revision, SessionState.EXPIRED)
        }
    }

    @Test fun `real password form cannot submit when disabled paused missing or cooling down`() = runTest {
        for (status in listOf(SavedLoginStatus(), SavedLoginStatus(saved = true),
            SavedLoginStatus(saved = true, enabled = true, paused = true),
            SavedLoginStatus(saved = true, enabled = true, retryAfter = Long.MAX_VALUE))) {
            val page = Page("password")
            assertThat(recoverAttendanceSession({ SessionState.EXPIRED }) {
                runSilentLogin(page, { error("Password permission was refused") }, { false },
                    timeoutMs = 2_000,
                    authorizeCredentials = { status.canAttemptAt(testScheduler.currentTime) })
            }).isFalse()
            assertThat(page.passwordSubmissions).isEqualTo(0)
        }
    }

    @Test fun `only a fresh expiry and an enabled real form can submit a password`() = runTest {
        val status = SavedLoginStatus(saved = true, enabled = true)
        val page = Page("password")
        var liveChecks = 0
        assertThat(recoverAttendanceSession({ liveChecks++; SessionState.EXPIRED }) {
            runSilentLogin(page, { true }, { page.authenticated },
                authorizeCredentials = { status.canAttemptAt(testScheduler.currentTime) })
        }).isTrue()
        assertThat(liveChecks).isEqualTo(1)
        assertThat(page.passwordSubmissions).isEqualTo(1)
    }

    @Test fun `working cookies and network errors never open password recovery`() = runTest {
        for (state in listOf(SessionState.AUTHENTICATED, SessionState.ERROR)) {
            assertThat(recoverAttendanceSession({ state }) { error("No rejected login") })
                .isEqualTo(state == SessionState.AUTHENTICATED)
        }
    }
}
