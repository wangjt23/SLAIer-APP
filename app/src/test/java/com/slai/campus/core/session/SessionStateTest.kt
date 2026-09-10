package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The two properties that keep the UI honest:
 *  - `ERROR` ("could not determine") must never be treated as a durable fact;
 *  - a background sync may only be attempted for a state that is worth trying.
 */
class SessionStateTest {

    @Test
    fun `definitive states are the ones we actually determined`() {
        assertThat(SessionState.AUTHENTICATED.isDefinitive).isTrue()
        assertThat(SessionState.EXPIRED.isDefinitive).isTrue()
        assertThat(SessionState.NEEDS_LOGIN.isDefinitive).isTrue()
    }

    @Test
    fun `unknown and error are not definitive`() {
        assertThat(SessionState.UNKNOWN.isDefinitive).isFalse()
        assertThat(SessionState.ERROR.isDefinitive).isFalse()
        assertThat(SessionState.AUTHENTICATING.isDefinitive).isFalse()
    }

    @Test
    fun `offline probe must not erase a definitive state`() {
        // Mirrors SessionManager.probe: an ERROR result keeps the previous definitive state, so a
        // user who is demonstrably logged out still sees "需要重新登录" while offline.
        fun effective(previous: SessionState, probed: SessionState): SessionState =
            if (probed == SessionState.ERROR && previous.isDefinitive) previous else probed

        assertThat(effective(SessionState.EXPIRED, SessionState.ERROR)).isEqualTo(SessionState.EXPIRED)
        assertThat(effective(SessionState.AUTHENTICATED, SessionState.ERROR))
            .isEqualTo(SessionState.AUTHENTICATED)
        assertThat(effective(SessionState.NEEDS_LOGIN, SessionState.ERROR))
            .isEqualTo(SessionState.NEEDS_LOGIN)
        assertThat(effective(SessionState.UNKNOWN, SessionState.ERROR)).isEqualTo(SessionState.ERROR)
        // A real finding always wins over a previous guess.
        assertThat(effective(SessionState.UNKNOWN, SessionState.EXPIRED)).isEqualTo(SessionState.EXPIRED)
        assertThat(effective(SessionState.AUTHENTICATED, SessionState.EXPIRED))
            .isEqualTo(SessionState.EXPIRED)
    }

    @Test
    fun `only authenticated or unknown may trigger a background sync`() {
        assertThat(SessionState.AUTHENTICATED.canAttemptSync).isTrue()
        assertThat(SessionState.UNKNOWN.canAttemptSync).isTrue()
        assertThat(SessionState.EXPIRED.canAttemptSync).isFalse()
        assertThat(SessionState.NEEDS_LOGIN.canAttemptSync).isFalse()
        assertThat(SessionState.ERROR.canAttemptSync).isFalse()
    }

    @Test
    fun `session snapshot keeps the two systems independent`() {
        val snapshot = SessionSnapshot(sis = SessionState.AUTHENTICATED, stu = SessionState.NEEDS_LOGIN)
        assertThat(snapshot.stateOf(com.slai.campus.core.common.SchoolSystem.SIS))
            .isEqualTo(SessionState.AUTHENTICATED)
        assertThat(snapshot.stateOf(com.slai.campus.core.common.SchoolSystem.STU))
            .isEqualTo(SessionState.NEEDS_LOGIN)
    }
}
