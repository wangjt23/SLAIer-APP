package com.slai.campus.core.session

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/** A fresh successful attendance request can finish login even while WebView/JS is still loading. */
internal suspend fun awaitAutomaticLoginResult(
    awaitFreshAuthentication: suspend () -> Unit,
    hasFreshAuthentication: () -> Boolean,
    signIn: suspend () -> Boolean
): Boolean = coroutineScope {
    val confirmed = async(start = CoroutineStart.LAZY) { awaitFreshAuthentication(); true }
    val login = async(start = CoroutineStart.LAZY) { signIn() }
    try {
        select {
            confirmed.onAwait { true }
            login.onAwait { it || hasFreshAuthentication() }
        }
    } finally {
        confirmed.cancel()
        login.cancel()
    }
}
