package com.slai.campus.core.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** These events permit a session probe; they never establish that a password should be submitted. */
internal enum class AutomaticLoginWakeup { COOLDOWN_FINISHED, NETWORK_CHANGED }

/**
 * Cookie recovery listens to network changes whenever the app is available in the foreground,
 * regardless of password settings. Only the password cooldown depends on saved-login eligibility.
 * Re-entering the app waits for the persisted deadline instead of resetting the cooldown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun automaticLoginWakeups(
    foregroundAvailable: Flow<Boolean>,
    savedLogin: Flow<SavedLoginStatus>,
    networkChanges: Flow<Long>,
    now: () -> Long = System::currentTimeMillis
): Flow<AutomaticLoginWakeup> = flow {
    // Kept outside flatMapLatest so foreground changes cannot repeatedly emit the same expiry.
    var deliveredCooldown: Long? = null
    val cooldownWakeups = combine(foregroundAvailable, savedLogin) { foreground, status ->
        val active = foreground && status.saved && status.enabled && !status.paused
        if (active) status.retryAfter else 0L
    }.distinctUntilChanged().flatMapLatest { retryAfter ->
        flow cooldown@{
            if (retryAfter <= 0L || retryAfter == deliveredCooldown) return@cooldown
            // Recheck after delay in case the wall clock was moved backwards while waiting.
            while (true) {
                val remaining = retryAfter - now()
                if (remaining <= 0) break
                delay(remaining)
            }
            deliveredCooldown = retryAfter
            emit(AutomaticLoginWakeup.COOLDOWN_FINISHED)
        }
    }
    val networkWakeups = foregroundAvailable.distinctUntilChanged().flatMapLatest { foreground ->
        if (foreground) networkChanges.map { AutomaticLoginWakeup.NETWORK_CHANGED } else emptyFlow()
    }
    merge(cooldownWakeups, networkWakeups).collect { emit(it) }
}

/** UI-only countdown. Collecting this flow never performs a network request or starts a login. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun savedLoginRetrySeconds(
    savedLogin: Flow<SavedLoginStatus>,
    now: () -> Long = System::currentTimeMillis
): Flow<Int> = savedLogin.map { status ->
    if (status.saved && status.enabled && !status.paused) status.retryAfter else 0L
}.distinctUntilChanged().flatMapLatest { retryAfter ->
    flow {
        while (true) {
            val remaining = (retryAfter - now()).coerceAtLeast(0)
            val seconds = (remaining / 1_000 + if (remaining % 1_000 > 0) 1 else 0)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            emit(seconds)
            if (remaining == 0L) break
            // A partial second expires first; following ticks align with the saved deadline.
            delay((remaining % 1_000).takeIf { it > 0 } ?: 1_000)
        }
    }
}.distinctUntilChanged()
