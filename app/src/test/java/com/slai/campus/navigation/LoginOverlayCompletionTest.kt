package com.slai.campus.navigation

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.SchoolSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginOverlayCompletionTest {
    private fun page() = Overlay.Web("https://sis.slai.edu.cn/yjsxt/htxylogin", "登录", SchoolSystem.SIS,
        isLoginFlow = true)

    @Test fun `completion closes only its own page after the success notice`() = runTest {
        val login = page()
        var overlay: Overlay? = login
        var closed = false
        launch { closed = closeCompletedLoginOverlay(login.flowId, { overlay }, { overlay = null }) }
        runCurrent()
        advanceTimeBy(599)
        assertThat(overlay).isEqualTo(login)
        advanceUntilIdle()
        assertThat(overlay).isNull()
        assertThat(closed).isTrue()
    }

    @Test fun `reopening the same login URL during the notice preserves the new page`() = runTest {
        val first = page()
        val second = page()
        var overlay: Overlay? = first
        var closed = false
        launch { closed = closeCompletedLoginOverlay(first.flowId, { overlay }, { overlay = null }) }
        runCurrent()
        advanceTimeBy(300)
        overlay = second
        advanceUntilIdle()
        assertThat(overlay).isEqualTo(second)
        assertThat(closed).isFalse()
    }

    @Test fun `queued old completion does not close a later login or unrelated overlay`() = runTest {
        val first = page()
        val second = page()
        var overlay: Overlay? = second
        assertThat(closeCompletedLoginOverlay(first.flowId, { overlay }, { overlay = null })).isFalse()
        assertThat(overlay).isEqualTo(second)
        overlay = Overlay.Diagnostics
        assertThat(closeCompletedLoginOverlay(first.flowId, { overlay }, { overlay = null })).isFalse()
        assertThat(overlay).isEqualTo(Overlay.Diagnostics)
    }

    @Test fun `browsing and capture flows are never closed by login confirmation`() = runTest {
        listOf(page().copy(closeOnLogin = false), page().copy(captureEnabled = true),
            page().copy(isLoginFlow = false)).forEach { page ->
            var overlay: Overlay? = page
            assertThat(closeCompletedLoginOverlay(page.flowId, { overlay }, { overlay = null })).isFalse()
            assertThat(overlay).isEqualTo(page)
        }
    }
}
