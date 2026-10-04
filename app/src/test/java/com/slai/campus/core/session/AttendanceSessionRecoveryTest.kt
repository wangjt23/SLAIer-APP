package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AttendanceSessionRecoveryTest {
    @Test fun `a valid attendance response needs no SSO or password login`() = runTest {
        assertThat(recoverAttendanceSession({ SessionState.AUTHENTICATED }, { error("must not log in") })).isTrue()
    }

    @Test fun `network or unknown failures never start authentication`() = runTest {
        for (state in listOf(SessionState.ERROR, SessionState.UNKNOWN, SessionState.NEEDS_LOGIN)) {
            assertThat(recoverAttendanceSession({ state }, { error("must not log in") })).isFalse()
        }
    }

    @Test fun `fresh expiration is verified before silent SSO recovery`() = runTest {
        val order = mutableListOf<String>()
        assertThat(recoverAttendanceSession(
            { order += "verify"; SessionState.EXPIRED },
            { order += "sso"; true }
        )).isTrue()
        assertThat(order).containsExactly("verify", "sso").inOrder()
    }

    @Test fun `failed recovery remains a failure`() = runTest {
        assertThat(recoverAttendanceSession({ SessionState.EXPIRED }, { false })).isFalse()
    }
}
