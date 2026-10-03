package com.slai.campus.core.web

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SilentLoginFlowTest {
    private class Page : SilentLoginPage {
        override var url: String? = "https://sts.slai.edu.cn/adfs/oauth2/authorize"
        override var failed = false
        override var loaded = true
        override var generation = 1
        var step = "username"
        var usernameSubmissions = 0
        var passwordSubmissions = 0
        var onPassword: () -> Unit = { url = "https://stu.slai.edu.cn/a/index" }
        override suspend fun inspect() = step
        override suspend fun submit(password: Boolean): String {
            if (password) { passwordSubmissions++; onPassword() }
            else { usernameSubmissions++; step = "password" }
            return "submitted"
        }
    }

    @Test fun `two-step login succeeds only after authenticated probe`() = runTest {
        val page = Page()
        var authorized = 0
        var probes = 0
        assertThat(runSilentLogin(page, { authorized++; true }, { probes++; page.url == "https://stu.slai.edu.cn/a/index" })).isTrue()
        assertThat(page.usernameSubmissions).isEqualTo(1)
        assertThat(page.passwordSubmissions).isEqualTo(1)
        assertThat(authorized).isEqualTo(1)
        assertThat(probes).isAtLeast(1)
    }

    @Test fun `existing SSO session needs no password submission`() = runTest {
        val page = Page().apply { url = "https://stu.slai.edu.cn/a/index" }
        assertThat(runSilentLogin(page, { error("must not submit password") }, { true })).isTrue()
        assertThat(page.passwordSubmissions).isEqualTo(0)
    }

    @Test fun `captcha before password ends with manual failure`() = runTest {
        val page = Page().apply { step = "manual" }
        assertThat(runSilentLogin(page, { error("must not submit password") }, { false })).isFalse()
        assertThat(page.usernameSubmissions).isEqualTo(0)
        assertThat(page.passwordSubmissions).isEqualTo(0)
    }

    @Test fun `captcha after password never retries`() = runTest {
        val page = Page().apply { onPassword = { step = "manual" } }
        assertThat(runSilentLogin(page, { true }, { false })).isFalse()
        assertThat(page.passwordSubmissions).isEqualTo(1)
    }

    @Test fun `reloaded password form fails without another submission`() = runTest {
        val page = Page().apply { onPassword = { generation++ } }
        assertThat(runSilentLogin(page, { true }, { false })).isFalse()
        assertThat(page.passwordSubmissions).isEqualTo(1)
    }

    @Test fun `stuck form times out and does not repeatedly submit`() = runTest {
        val page = Page().apply { onPassword = {} }
        assertThat(runSilentLogin(page, { true }, { false }, timeoutMs = 2_000)).isFalse()
        assertThat(testScheduler.currentTime).isEqualTo(2_000)
        assertThat(page.passwordSubmissions).isEqualTo(1)
    }

    @Test fun `main-frame network or TLS failure ends immediately`() = runTest {
        val page = Page().apply { failed = true }
        assertThat(runSilentLogin(page, { error("must not submit") }, { false })).isFalse()
        assertThat(testScheduler.currentTime).isEqualTo(0)
    }

    @Test fun `untrusted navigation never receives credentials`() = runTest {
        for (url in listOf("http://stu.slai.edu.cn/a/index", "https://evil.example/stu.slai.edu.cn/a/index",
            "https://stu.slai.edu.cn:444/a/index", "https://user@sts.slai.edu.cn/adfs/ls/")) {
            val page = Page().apply { this.url = url }
            assertThat(runSilentLogin(page, { error("must not submit") }, { error("must not probe") })).isFalse()
        }
    }

    @Test fun `failed authenticated probe is not treated as success`() = runTest {
        val page = Page().apply { url = "https://stu.slai.edu.cn/a/index" }
        assertThat(runSilentLogin(page, { true }, { false })).isFalse()
    }

    @Test fun `probe is covered by the overall deadline`() = runTest {
        val page = Page().apply { url = "https://stu.slai.edu.cn/a/index" }
        assertThat(runSilentLogin(page, { true }, { delay(60_000); true }, timeoutMs = 2_000)).isFalse()
        assertThat(testScheduler.currentTime).isEqualTo(2_000)
    }

    @Test fun `opening manual login or leaving app cancels the attempt`() = runTest {
        val page = Page().apply { step = "none" }
        val job = async { runSilentLogin(page, { error("must not submit") }, { false }) }
        runCurrent()
        advanceTimeBy(500)
        job.cancelAndJoin()
        assertThat(job.isCancelled).isTrue()
        assertThat(page.passwordSubmissions).isEqualTo(0)
    }

    @Test fun `session success does not wait for onPageFinished or a particular landing path`() = runTest {
        for (url in listOf("https://stu.slai.edu.cn/a", "https://stu.slai.edu.cn/sso/code", "https://stu.slai.edu.cn/portal")) {
            val page = Page().apply { this.url = url; loaded = false }
            val start = testScheduler.currentTime
            assertThat(runSilentLogin(page, { error("no password needed") }, { delay(100); true })).isTrue()
            assertThat(testScheduler.currentTime - start).isEqualTo(100)
        }
    }

    @Test fun `cookies becoming usable during a stuck page finish promptly`() = runTest {
        val page = Page().apply { loaded = false }
        assertThat(runSilentLogin(page, { error("no form loaded") }, { testScheduler.currentTime >= 2_000 })).isTrue()
        assertThat(testScheduler.currentTime).isLessThan(4_000)
    }

    @Test fun `unresponsive javascript does not block real session confirmation`() = runTest {
        val page = object : SilentLoginPage {
            override val url = "https://sts.slai.edu.cn/adfs/ls/"
            override val loaded = true
            override val failed = false
            override val generation = 1
            override suspend fun inspect(): String { delay(60_000); return "none" }
            override suspend fun submit(password: Boolean) = "none"
        }
        assertThat(runSilentLogin(page, { false }, { delay(100); true })).isTrue()
        assertThat(testScheduler.currentTime).isEqualTo(100)
    }
}
