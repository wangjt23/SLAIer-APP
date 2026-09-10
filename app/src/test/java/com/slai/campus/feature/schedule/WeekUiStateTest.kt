package com.slai.campus.feature.schedule

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.session.SessionState
import com.slai.campus.domain.schedule.RefreshResult
import org.junit.Test

/**
 * Regression tests for the *reporting* half of the timetable bug.
 *
 * The reason is asserted as a **structured value**, not as a sentence: the ViewModel must not embed
 * Chinese copy, otherwise the UI cannot be localised (that is exactly what made the app monolingual).
 * The Chinese/English wording itself is now a resource string, resolved in the UI.
 *
 * The screen used to render one string — "暂无课表数据" — for every failure. A dead session, a changed
 * endpoint and a genuinely empty semester were indistinguishable, so the only actionable case
 * (log in again) looked exactly like the harmless one. These tests pin the distinction down.
 */
class WeekUiStateTest {

    @Test
    fun `expired refresh result asks for a login even when the state machine still says authenticated`() {
        // The refresh knows before the next probe does; the UI must follow the fresher signal.
        val state = WeekUiState(
            lastRefresh = RefreshResult.SessionExpired,
            sisState = SessionState.AUTHENTICATED
        )
        assertThat(state.needsLogin).isTrue()
        assertThat(state.emptyReason).isEqualTo(EmptyReason.SessionExpired)
    }

    @Test
    fun `expired session state asks for a login even before any refresh ran`() {
        val state = WeekUiState(lastRefresh = null, sisState = SessionState.EXPIRED)
        assertThat(state.needsLogin).isTrue()
    }

    @Test
    fun `never logged in says so instead of blaming the server`() {
        val state = WeekUiState(lastRefresh = null, sisState = SessionState.NEEDS_LOGIN)
        assertThat(state.needsLogin).isTrue()
        assertThat(state.emptyReason).isEqualTo(EmptyReason.NeverSignedIn)
    }

    @Test
    fun `a 901 reported as a server error is still translated into a login request`() {
        // 901 == "no valid session" on this deployment, verified against the live server. Even if it
        // ever arrives through the generic ServerError branch, the user must be told to log in.
        val state = WeekUiState(lastRefresh = RefreshResult.ServerError(901))
        assertThat(state.needsLogin).isTrue()
        assertThat(state.emptyReason).isEqualTo(EmptyReason.SessionExpired)
    }

    @Test
    fun `a real server error keeps its code and does not ask for a login`() {
        val state = WeekUiState(lastRefresh = RefreshResult.ServerError(502))
        assertThat(state.needsLogin).isFalse()
        assertThat(state.emptyReason).isEqualTo(EmptyReason.ServerError(502))
    }

    @Test
    fun `a schema change is reported verbatim so the reason is actionable`() {
        val state = WeekUiState(
            lastRefresh = RefreshResult.SchemaChanged("缺少 kbList 字段（顶层字段：code, msg）")
        )
        assertThat(state.needsLogin).isFalse()
        assertThat((state.emptyReason as EmptyReason.SchemaChanged).detail).contains("缺少 kbList 字段")
    }

    @Test
    fun `offline without cache is distinguishable from offline with cache`() {
        val state = WeekUiState(lastRefresh = RefreshResult.Offline(cached = false))
        assertThat(state.needsLogin).isFalse()
        assertThat(state.emptyReason).isEqualTo(EmptyReason.OfflineNoCache)
    }

    @Test
    fun `a fresh install explains that nothing has been synced yet`() {
        val state = WeekUiState()
        assertThat(state.needsLogin).isFalse()
        assertThat(state.emptyReason).isEqualTo(EmptyReason.NeverSynced)
    }
}
