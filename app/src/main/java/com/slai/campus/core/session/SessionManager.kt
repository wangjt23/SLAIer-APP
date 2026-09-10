package com.slai.campus.core.session

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.SchoolSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A cheap "am I still logged in?" check against a protected endpoint of one school system.
 *
 * Implemented per system in `data/sis` and `data/stu` and contributed through Hilt so that
 * `core` never depends on `data`.
 */
interface SessionProbe {
    val system: SchoolSystem
    suspend fun probe(): SessionState
}

/**
 * Owns the two independent state machines described in the design plan.
 *
 * Transitions are persisted, so the app remembers after a cold start that SIS needs a login while STU
 * is still authenticated. Nothing here ever opens a login UI by itself: background work must stop
 * quietly when a session is gone rather than interrupt the user with a WebView.
 */
@Singleton
class SessionManager @Inject constructor(
    private val store: SessionStore,
    probes: Set<@JvmSuppressWildcards SessionProbe>
) {

    private val probesBySystem: Map<SchoolSystem, SessionProbe> = probes.associateBy { it.system }
    private val probeMutex = Mutex()

    private val _state = MutableStateFlow(SessionSnapshot())
    val state: StateFlow<SessionSnapshot> = _state.asStateFlow()

    /** Called once at app start (and after a login WebView finishes) to hydrate from disk. */
    suspend fun hydrate() {
        val persisted = store.current()
        _state.value = persisted
        AppLog.i("session hydrated sis=${persisted.sis} stu=${persisted.stu}")
    }

    suspend fun set(system: SchoolSystem, newState: SessionState) {
        val previous = _state.value
        if (previous.stateOf(system) == newState) return
        // ERROR means "could not determine". It is a transient observation, not a durable fact, so it
        // is kept in memory only: a restart must not turn a known "logged out" into "unknown".
        if (newState != SessionState.ERROR) {
            store.setState(system, newState)
        }
        _state.value = when (system) {
            SchoolSystem.SIS -> previous.copy(sis = newState)
            SchoolSystem.STU -> previous.copy(stu = newState)
        }
        AppLog.i("session $system: ${previous.stateOf(system)} -> $newState")
    }

    suspend fun setAccountHash(hash: String?) {
        store.setAccountHash(hash)
        _state.value = _state.value.copy(accountHash = hash)
    }

    /** The cache key for the current account; creates a device-local one when the id is unknown. */
    suspend fun requireAccountHash(): String {
        _state.value.accountHash?.let { return it }
        val hash = AccountHasher.deviceLocalHash(store.studentIdHint())
        setAccountHash(hash)
        return hash
    }

    /**
     * Re-derives the cache key from a student id discovered in a response. Returns true when the
     * key changed, which means the previous cache belongs to a different account and must not be
     * shown.
     */
    suspend fun onStudentIdDiscovered(rawStudentId: String?): Boolean {
        val id = rawStudentId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (id.length < 3) return false
        store.setStudentIdHint(id)
        val newHash = AccountHasher.hash(id)
        val oldHash = _state.value.accountHash
        if (oldHash == newHash) return false
        setAccountHash(newHash)
        AppLog.i("account changed; cache partition switched")
        return true
    }

    suspend fun current(): SessionSnapshot = _state.value

    suspend fun stateOf(system: SchoolSystem): SessionState = _state.value.stateOf(system)

    /**
     * Runs the probe for one system (or both) and applies the resulting transition.
     * Returns the state that was determined.
     *
     * `ERROR` means "could not determine" (offline, server down). It must never erase a state we
     * already established: if the user was demonstrably logged out and then lost signal, the app must
     * keep saying "需要重新登录" rather than degrade to a vague "无法检测".
     */
    suspend fun probe(system: SchoolSystem): SessionState = probeMutex.withLock {
        val probe = probesBySystem[system] ?: return@withLock SessionState.ERROR
        val result = runCatching { probe.probe() }
            .onFailure { AppLog.w("probe $system failed: ${it.javaClass.simpleName}") }
            .getOrDefault(SessionState.ERROR)

        val current = _state.value.stateOf(system)
        val effective = if (result == SessionState.ERROR && current.isDefinitive) current else result
        set(system, effective)
        effective
    }

    suspend fun probeAll(): SessionSnapshot {
        SchoolSystem.entries.forEach { probe(it) }
        return _state.value
    }

    /** The user explicitly signed out: drop cookies and mark both systems as needing a login. */
    suspend fun signOut(system: SchoolSystem, cookies: WebCookieBridge) {
        cookies.clearHostCookies(system.host)
        set(system, SessionState.NEEDS_LOGIN)
    }

    /** Waits until [system] is authenticated, or gives up after [timeoutMillis]. */
    suspend fun awaitAuthenticated(system: SchoolSystem, timeoutMillis: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (stateOf(system) == SessionState.AUTHENTICATED) return true
            kotlinx.coroutines.delay(150)
        }
        return stateOf(system) == SessionState.AUTHENTICATED
    }

    /** Convenience for workers: never start a background sync that would immediately 401. */
    suspend fun shouldSync(system: SchoolSystem): Boolean {
        val snapshot = state.first()
        return snapshot.stateOf(system).canAttemptSync
    }
}
