package com.slai.campus.core.session

import com.slai.campus.core.common.SchoolSystem

/**
 * Per-system authentication state.
 *
 * The plan's hard rule: never collapse this into a single `isLoggedIn` boolean. SIS and STU are
 * separate OAuth clients against the same AD FS, they have separate cookies and separate lifetimes,
 * so `sis = AUTHENTICATED, stu = NEEDS_LOGIN` is a perfectly normal state that must be representable.
 */
enum class SessionState {
    /** Nothing has been probed yet on this install. */
    UNKNOWN,

    /** A login WebView is open and the user is working through the school flow. */
    AUTHENTICATING,

    /** A probe against a protected endpoint succeeded. */
    AUTHENTICATED,

    /** The endpoint answered, but with the login page / a login redirect. */
    EXPIRED,

    /** We know a login is required (e.g. the user cleared cookies). */
    NEEDS_LOGIN,

    /** Could not determine (offline, server down). Distinct from EXPIRED. */
    ERROR;

    val canAttemptSync: Boolean
        get() = this == AUTHENTICATED || this == UNKNOWN

    /**
     * True when the state is a real finding rather than "we have not looked / could not look".
     * Used so an offline probe cannot wipe out a known `EXPIRED` / `AUTHENTICATED` result.
     */
    val isDefinitive: Boolean
        get() = this == AUTHENTICATED || this == EXPIRED || this == NEEDS_LOGIN
}

/** A snapshot of both systems, which is what the UI actually needs. */
data class SessionSnapshot(
    val sis: SessionState = SessionState.UNKNOWN,
    val stu: SessionState = SessionState.UNKNOWN,
    val accountHash: String? = null
) {
    fun stateOf(system: SchoolSystem): SessionState = when (system) {
        SchoolSystem.SIS -> sis
        SchoolSystem.STU -> stu
    }
}
