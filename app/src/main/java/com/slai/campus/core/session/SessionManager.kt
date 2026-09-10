package com.slai.campus.core.session

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.ApplicationScope
import com.slai.campus.core.common.SchoolSystem
import kotlinx.coroutines.CoroutineScope
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

    /** 尚未落盘的两个系统状态。key 只在"值还没写进 DataStore"期间存在。 */
    private val pending = MutableStateFlow<Map<SchoolSystem, SessionState>>(emptyMap())

    /** 尚未落盘的 accountHash（null 表示"没有待写值"，也可能表示"待写的就是 null"，用单独标志区分）。 */
    private val pendingHash = MutableStateFlow<PendingHash?>(null)

    private data class PendingHash(val value: String?)

    val state: StateFlow<SessionSnapshot> = combine(
        store.snapshot,
        pending,
        pendingHash
    ) { persisted, pendingStates, pendingAccountHash ->
        SessionSnapshot(
            sis = pendingStates[SchoolSystem.SIS] ?: persisted.sis,
            stu = pendingStates[SchoolSystem.STU] ?: persisted.stu,
            accountHash = pendingAccountHash?.value ?: persisted.accountHash
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
                pendingHash.update { inFlight ->
                    if (inFlight != null && persisted.accountHash == inFlight.value) null else inFlight
                }
            }
        }
    }

    suspend fun set(system: SchoolSystem, newState: SessionState) {
        val previous = stateOf(system)
        if (previous == newState) return

        // 先写 overlay：状态机的读者（UI、仓库、worker）必须能立刻看到这次转换。
        pending.update { it + (system to newState) }
        // ERROR 是"这次没探出来"，属于瞬时观察而非持久事实，落盘会把已知的"已登出"洗成"无法检测"。
        if (newState != SessionState.ERROR) {
            store.setState(system, newState)
        }
        AppLog.i("session $system: $previous -> $newState")
    }

    suspend fun setAccountHash(hash: String?) {
        pendingHash.value = PendingHash(hash)
        store.setAccountHash(hash)
    }

    /** The cache key for the current account; creates a device-local one when the id is unknown. */
    suspend fun requireAccountHash(): String {
        state.value.accountHash?.let { return it }
        // 内存快照可能还没跟上磁盘（冷启动的头几毫秒）。必须再问一次真相源：
        // deviceLocalHash(null) 每次都会生成一个**新的随机 key**，重复走一遍会把缓存分区换掉，
        // 用户已经缓存的课表会看起来"凭空消失"。
        store.current().accountHash?.let { return it }
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
        val oldHash = state.value.accountHash
        if (oldHash == newHash) return false
        setAccountHash(newHash)
        AppLog.i("account changed; cache partition switched")
        return true
    }

    suspend fun current(): SessionSnapshot = state.value

    suspend fun stateOf(system: SchoolSystem): SessionState = state.value.stateOf(system)

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

        val current = stateOf(system)
        val effective = if (result == SessionState.ERROR && current.isDefinitive) current else result
        set(system, effective)
        effective
    }

    suspend fun probeAll(): SessionSnapshot {
        SchoolSystem.entries.forEach { probe(it) }
        return state.value
    }

    /** The user explicitly signed out: drop cookies and mark both systems as needing a login. */
    suspend fun signOut(system: SchoolSystem, cookies: WebCookieBridge) {
        cookies.clearHostCookies(system.host)
        set(system, SessionState.NEEDS_LOGIN)
    }

    /** Convenience for workers: never start a background sync that would immediately 401. */
    suspend fun shouldSync(system: SchoolSystem): Boolean {
        val snapshot = state.first()
        return snapshot.stateOf(system).canAttemptSync
    }
}
