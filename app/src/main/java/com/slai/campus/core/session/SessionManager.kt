package com.slai.campus.core.session

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.ApplicationScope
import com.slai.campus.core.common.SchoolSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 *
 * **唯一真相源是 DataStore。** 这里不再自己存一份 [SessionSnapshot] 再靠 `hydrate()` 手工对齐
 * （那样的双真相源会互相滞后，只能靠 `needsLogin` 里的多重 OR 打补丁）。派生流之上只叠一层
 * [pending] overlay，用来承载**还没落盘的写入**：
 *
 *  - 写入后立刻可见（不必等 DataStore 的发射），
 *  - 磁盘追上来之后自动出栈，
 *  - [SessionState.ERROR]（"这次没探出来"）永远不进 overlay 的落盘路径，重启后不会把已知的
 *    "已登出"降级成"无法检测"。
 */
@Singleton
class SessionManager @Inject constructor(
    private val store: SessionStore,
    probes: Set<@JvmSuppressWildcards SessionProbe>,
    @ApplicationScope scope: CoroutineScope
) {

    private val probesBySystem: Map<SchoolSystem, SessionProbe> = probes.associateBy { it.system }
    private val probeMutex = Mutex()
    private val accountMutex = Mutex()
    private val accountVersions = MutableStateFlow<Map<SchoolSystem, Long>>(emptyMap())
    private data class OwnerBinding(val hash: String, val from: Long, val to: Long)
    private val ownerBindings = mutableMapOf<SchoolSystem, OwnerBinding>()
    // In-memory evidence from completed network requests, never hydrated from cached login state.
    private val authenticationEvidence = AuthenticationEvidence()

    fun authenticationVersion(system: SchoolSystem): Long = authenticationEvidence.version(system)

    suspend fun awaitAuthenticationAfter(system: SchoolSystem, version: Long) {
        authenticationEvidence.awaitAfter(system, version)
    }

    private fun recordAuthentication(system: SchoolSystem) {
        authenticationEvidence.confirmed(system)
    }

    /** 尚未落盘的两个系统状态。key 只在"值还没写进 DataStore"期间存在。 */
    private val pending = MutableStateFlow<Map<SchoolSystem, SessionState>>(emptyMap())

    val state: StateFlow<SessionSnapshot> = combine(
        store.snapshot,
        pending
    ) { persisted, pendingStates ->
        SessionSnapshot(
            sis = pendingStates[SchoolSystem.SIS] ?: persisted.sis,
            stu = pendingStates[SchoolSystem.STU] ?: persisted.stu,
            sisAccountHash = persisted.sisAccountHash,
            stuAccountHash = persisted.stuAccountHash
        )
    }.stateIn(scope, SharingStarted.Eagerly, SessionSnapshot())

    init {
        // 磁盘追上来之后就把 overlay 里的条目摘掉。这张表只承载"在途写入"，不复制状态本身，
        // 所以它不会变成第二个真相源。
        scope.launch {
            store.snapshot.collect { persisted ->
                pending.update { inFlight ->
                    inFlight.filter { (system, value) -> persisted.stateOf(system) != value }
                }
            }
        }
    }

    suspend fun set(system: SchoolSystem, newState: SessionState, expectedAccountVersion: Long? = null,
                    expectedAuthenticationVersion: Long? = null): Boolean {
        return if (expectedAccountVersion == null && expectedAuthenticationVersion == null) {
            setState(system, newState)
            true
        } else accountMutex.withLock {
            if ((expectedAccountVersion == null || accountVersion(system) == expectedAccountVersion) &&
                (expectedAuthenticationVersion == null || authenticationVersion(system) == expectedAuthenticationVersion)) {
                setState(system, newState)
                true
            } else false
        }
    }

    private suspend fun setState(system: SchoolSystem, newState: SessionState) {
        val previous = stateOf(system)
        if (previous == newState) {
            if (newState == SessionState.AUTHENTICATED) recordAuthentication(system)
            return
        }

        // 先写 overlay：状态机的读者（UI、仓库、worker）必须能立刻看到这次转换。
        pending.update { it + (system to newState) }
        // ERROR 是"这次没探出来"，属于瞬时观察而非持久事实，落盘会把已知的"已登出"洗成"无法检测"。
        if (newState != SessionState.ERROR) {
            store.setState(system, newState)
        }
        AppLog.i("session $system: $previous -> $newState")
        if (newState == SessionState.AUTHENTICATED) recordAuthentication(system)
    }

    fun accountVersion(system: SchoolSystem): Long = accountVersions.value[system] ?: 0

    fun responseIsCurrent(system: SchoolSystem, accountVersion: Long, authenticationVersion: Long): Boolean =
        this.accountVersion(system) == accountVersion && this.authenticationVersion(system) == authenticationVersion

    private suspend fun writeAccountHash(system: SchoolSystem, hash: String?) {
        store.setAccountHash(system, hash)
    }

    /** Atomically initializes an independent partition for each school system. */
    suspend fun requireAccountHash(system: SchoolSystem): String = accountMutex.withLock {
        store.requireAccountHash(system)
    }

    /** Identity selection and cache writes form one operation relative to explicit account changes. */
    suspend fun <T> withAccount(
        system: SchoolSystem,
        verifiedHash: String?,
        expectedVersion: Long,
        block: suspend (String) -> T
    ): T? = accountMutex.withLock {
        val currentVersion = accountVersion(system)
        if (currentVersion != expectedVersion) {
            // A startup probe may bind this same owner before the month response arrives.
            // Only that exact owner-binding transition can accept a same-owner response;
            // explicit login/logout always removes this allowance.
            val binding = ownerBindings[system]
            if (verifiedHash == null || binding == null || binding.hash != verifiedHash ||
                binding.from != expectedVersion || binding.to != currentVersion) return@withLock null
        }
        val selected = verifiedHash ?: if (store.accountPending(system)) AccountHasher.deviceLocalHash(null)
            else store.requireAccountHash(system)
        val previous = store.current().accountHashOf(system)
        if (previous != selected) {
            val next = currentVersion + 1
            accountVersions.update { it + (system to next) }
            ownerBindings[system] = OwnerBinding(selected, currentVersion, next)
        }
        writeAccountHash(system, selected)
        block(selected)
    }

    /** Validate a completed request without selecting an owner or clearing a pending login. */
    suspend fun <T> withCurrentAccount(
        system: SchoolSystem,
        owner: String,
        expectedVersion: Long,
        expectedAuthenticationVersion: Long?,
        block: (String, Long) -> T
    ): T? = accountMutex.withLock {
        val currentVersion = accountVersion(system)
        val binding = ownerBindings[system]
        val sameBinding = binding?.let {
            it.hash == owner && it.from == expectedVersion && it.to == currentVersion
        } == true
        if ((currentVersion != expectedVersion && !sameBinding) ||
            store.current().accountHashOf(system) != owner ||
            (expectedAuthenticationVersion != null && authenticationVersion(system) != expectedAuthenticationVersion)) return@withLock null
        block(owner, currentVersion)
    }

    /** Keep the last cache readable on cancellation; the next authenticated response selects an owner. */
    suspend fun beginLogin(system: SchoolSystem) = accountMutex.withLock {
        accountVersions.update { it + (system to (accountVersion(system) + 1)) }
        ownerBindings.remove(system)
        store.markAccountPending(system)
    }

    suspend fun current(): SessionSnapshot {
        val persisted = store.current()
        return SessionSnapshot(
            sis = pending.value[SchoolSystem.SIS] ?: persisted.sis,
            stu = pending.value[SchoolSystem.STU] ?: persisted.stu,
            sisAccountHash = persisted.sisAccountHash, stuAccountHash = persisted.stuAccountHash
        )
    }

    suspend fun stateOf(system: SchoolSystem): SessionState = current().stateOf(system)

    /**
     * Runs the probe for one system (or both) and applies the resulting transition.
     * Returns the state that was determined.
     *
     * `ERROR` means "could not determine" (offline, server down). It must never erase a state we
     * already established: if the user was demonstrably logged out and then lost signal, the app must
     * keep saying "需要重新登录" rather than degrade to a vague "无法检测".
     */
    suspend fun probe(system: SchoolSystem, requireFresh: Boolean = false): SessionState = probeMutex.withLock {
        val probe = probesBySystem[system] ?: return@withLock SessionState.ERROR
        val versionBeforeProbe = authenticationVersion(system)
        val accountBeforeProbe = accountVersion(system)
        val result = try { probe.probe() } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.w("probe $system failed: ${error.javaClass.simpleName}")
            SessionState.ERROR
        }

        val current = stateOf(system)
        // A request sent with old cookies must not overwrite a newer successful attendance fetch.
        if (result != SessionState.AUTHENTICATED && authenticationVersion(system) > versionBeforeProbe) {
            return@withLock if (requireFresh) result else current
        }
        val effective = if (result == SessionState.ERROR && current.isDefinitive) current else result
        // Retaining an old state on transport failure is not new proof of authentication.
        if (result != SessionState.ERROR || !current.isDefinitive) {
            if (!set(system, effective, accountBeforeProbe,
                if (result == SessionState.AUTHENTICATED) null else versionBeforeProbe)) return@withLock SessionState.ERROR
        }
        if (requireFresh) result else effective
    }

    suspend fun probeAll(): SessionSnapshot {
        SchoolSystem.entries.forEach { probe(it) }
        return state.value
    }

    /** The user explicitly signed out: drop cookies and mark both systems as needing a login. */
    suspend fun signOut(system: SchoolSystem, cookies: WebCookieBridge) {
        accountMutex.withLock {
            accountVersions.update { it + (system to (accountVersion(system) + 1)) }
            ownerBindings.remove(system)
            store.markAccountPending(system)
            cookies.clearHostCookiesAndAwait(system.host)
            writeAccountHash(system, null)
            accountVersions.update { it + (system to (accountVersion(system) + 1)) }
            setState(system, SessionState.NEEDS_LOGIN)
        }
    }

    /** Convenience for workers: never start a background sync that would immediately 401. */
    suspend fun shouldSync(system: SchoolSystem): Boolean {
        val snapshot = state.first()
        return snapshot.stateOf(system).canAttemptSync
    }
}
