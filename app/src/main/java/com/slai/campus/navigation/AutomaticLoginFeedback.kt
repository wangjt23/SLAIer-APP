package com.slai.campus.navigation

import com.slai.campus.core.session.SessionState
import com.slai.campus.core.web.AutomaticLoginState

/** A transport failure or a saved cooldown must not look like rejected credentials. */
internal fun automaticLoginFeedback(
    usedCredentials: Boolean,
    manualRequired: Boolean,
    lastCheck: SessionState,
    coolingDown: Boolean
): AutomaticLoginState = when {
    manualRequired -> AutomaticLoginState.FAILED
    lastCheck == SessionState.ERROR -> AutomaticLoginState.NETWORK_UNAVAILABLE
    coolingDown -> AutomaticLoginState.COOLDOWN
    usedCredentials -> AutomaticLoginState.FAILED
    else -> AutomaticLoginState.IDLE
}
