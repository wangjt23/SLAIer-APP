package com.slai.campus.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.slai.campus.R

/** Remaining cooldown is supplied by the state owner; this label never starts its own timer. */
@Composable
fun savedLoginCooldownText(retrySeconds: Int): String =
    stringResource(R.string.saved_login_cooldown, formatSavedLoginRetryTime(retrySeconds))

internal fun formatSavedLoginRetryTime(retrySeconds: Int): String {
    val seconds = retrySeconds.coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
