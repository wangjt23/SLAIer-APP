package com.slai.campus.core.session

/** Cached EXPIRED is only a reason to check; only a fresh rejection allows SSO recovery. */
internal suspend fun recoverAttendanceSession(
    verify: suspend () -> SessionState,
    recover: suspend () -> Boolean
): Boolean = when (verify()) {
    SessionState.AUTHENTICATED -> true
    SessionState.EXPIRED -> recover()
    else -> false
}
