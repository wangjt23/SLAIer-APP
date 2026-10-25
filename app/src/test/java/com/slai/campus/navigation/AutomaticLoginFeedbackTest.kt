package com.slai.campus.navigation

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.web.AutomaticLoginState
import org.junit.Test

class AutomaticLoginFeedbackTest {
    @Test fun `unreachable session check reports connectivity without suggesting rejected credentials`() {
        for (usedPassword in listOf(false, true)) {
            assertThat(automaticLoginFeedback(usedPassword, false, SessionState.ERROR, true))
                .isEqualTo(AutomaticLoginState.NETWORK_UNAVAILABLE)
        }
    }

    @Test fun `challenge still requires manual login even if cooldown and network errors coexist`() {
        assertThat(automaticLoginFeedback(true, true, SessionState.ERROR, true))
            .isEqualTo(AutomaticLoginState.FAILED)
    }

    @Test fun `interrupted credential submission explains the persisted cooldown`() {
        assertThat(automaticLoginFeedback(true, false, SessionState.EXPIRED, true))
            .isEqualTo(AutomaticLoginState.COOLDOWN)
    }

    @Test fun `reopening during cooldown explains why no password was resubmitted`() {
        assertThat(automaticLoginFeedback(false, false, SessionState.EXPIRED, true))
            .isEqualTo(AutomaticLoginState.COOLDOWN)
    }

    @Test fun `credential failure without a cooldown retains the manual login notice`() {
        assertThat(automaticLoginFeedback(true, false, SessionState.EXPIRED, false))
            .isEqualTo(AutomaticLoginState.FAILED)
    }

    @Test fun `cookie only recovery stays quiet if it did not require manual input`() {
        assertThat(automaticLoginFeedback(false, false, SessionState.EXPIRED, false))
            .isEqualTo(AutomaticLoginState.IDLE)
    }
}
