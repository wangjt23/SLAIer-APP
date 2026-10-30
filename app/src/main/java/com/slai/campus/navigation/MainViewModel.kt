package com.slai.campus.navigation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.session.SessionManager
import com.slai.campus.core.web.AppUrlProvider
import com.slai.campus.core.web.CaptureSession
import com.slai.campus.core.web.SisEndpoints
import com.slai.campus.data.provider.CaptureBus
import com.slai.campus.core.session.SessionSnapshot
import com.slai.campus.core.session.SessionState
import com.slai.campus.domain.schedule.RefreshReason
import com.slai.campus.domain.attendance.attendanceDate
import com.slai.campus.domain.attendance.refreshCurrentAttendance
import com.slai.campus.domain.attendance.AttendanceRefreshResult
import com.slai.campus.domain.attendance.AttendanceRefreshOrigin
import com.slai.campus.domain.schedule.ScheduleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import com.slai.campus.core.web.AutomaticLoginState
import com.slai.campus.core.web.SilentSchoolLogin
import com.slai.campus.core.session.awaitAutomaticLoginResult
import com.slai.campus.core.session.recoverAttendanceSession
import com.slai.campus.core.session.ForegroundSessionRecovery
import com.slai.campus.core.session.DeferredPasswordRetry
import com.slai.campus.core.session.automaticLoginWakeups
import com.slai.campus.core.session.AutomaticLoginWakeup
import com.slai.campus.core.network.NetworkMonitor
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import com.slai.campus.data.stu.StuAttendanceSessionVerifier
import javax.inject.Inject

/**
 * Owns cross-screen state: the two session machines, the "did the login WebView finish?" handshake,
 * and app-start hydration.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val scheduleRepository: ScheduleRepository,
    private val attendanceRepository: com.slai.campus.domain.attendance.AttendanceRepository,
    private val timeProvider: com.slai.campus.core.common.TimeProvider,
    private val urlProvider: AppUrlProvider,
    private val captureBus: CaptureBus,
    private val updateRepository: com.slai.campus.domain.update.UpdateRepository,
    private val cookieBridge: com.slai.campus.core.session.WebCookieBridge,
    val savedLoginStore: com.slai.campus.core.session.SavedLoginStore,
    private val silentSchoolLogin: SilentSchoolLogin,
    private val attendanceSessionVerifier: StuAttendanceSessionVerifier,
    private val networkMonitor: NetworkMonitor,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    val session: StateFlow<SessionSnapshot> = sessionManager.state
    val savedLogin = savedLoginStore.status
    private val automaticForegroundAvailable = MutableStateFlow(false)
    private val _automaticLoginState = MutableStateFlow(AutomaticLoginState.IDLE)
    val automaticLoginState = _automaticLoginState.asStateFlow()
    private val attendanceRefreshGate = AttendanceRefreshGate()
    private val deferredPasswordRetry = DeferredPasswordRetry()
    private val sessionRecovery = ForegroundSessionRecovery(
        scope = viewModelScope,
        now = timeProvider::epochMillis,
        hasNetwork = { networkMonitor.hasNetwork },
        recover = ::restoreAttendanceSession,
        onNetworkUnavailable = { _automaticLoginState.value = AutomaticLoginState.NETWORK_UNAVAILABLE }
    )

    fun stopAutomaticLogin() {
        updateAutomaticLogin(foregroundAvailable = false)
    }

    /** Cookie recovery is always available in the foreground; password submission is opt-in. */
    fun updateAutomaticLogin(foregroundAvailable: Boolean) {
        automaticForegroundAvailable.value = foregroundAvailable
        sessionRecovery.update(foregroundAvailable, savedLogin.value.revision, session.value.stu)
        if (!foregroundAvailable) _automaticLoginState.value = AutomaticLoginState.IDLE
    }

    /** Entering attendance is a recovery event even if the persisted EXPIRED value did not change. */
    fun requestAttendanceRecovery() {
        sessionRecovery.requestCheck()
    }

    private suspend fun restoreAttendanceSession(revision: Long) {
        val status = savedLogin.value
        if (status.revision != revision) return
        val authenticationVersion = sessionManager.authenticationVersion(SchoolSystem.STU)
        fun freshlyAuthenticated() = sessionManager.authenticationVersion(SchoolSystem.STU) > authenticationVersion
        var usingCredentials = false
        var recoveringSession = false
        var manualRequired = false
        var lastCheck = SessionState.UNKNOWN
        suspend fun checkSession(): SessionState = verifyAttendanceSession().also {
            lastCheck = it
            // Consume our own definitive transition before the asynchronous UI collector sees it.
            // Otherwise a fast failed recovery can look like a new expiry after its job completed.
            if (it.isDefinitive) sessionRecovery.update(automaticForegroundAvailable.value,
                savedLogin.value.revision, it)
        }
        fun feedback() = automaticLoginFeedback(
            usingCredentials,
            manualRequired && status.saved && status.enabled && !status.paused,
            lastCheck,
            status.saved && status.enabled && !status.paused &&
                savedLogin.value.retryAfter > timeProvider.epochMillis()
        )
        suspend fun completed() {
            cookieBridge.flush()
            savedLoginStore.loginSucceeded(expectedRevision = revision)
            deferredPasswordRetry.clear()
            _automaticLoginState.value = if (usingCredentials) AutomaticLoginState.SUCCEEDED else AutomaticLoginState.IDLE
            // A valid cookie still needs new punch records after a long stay in the background.
            refreshAttendanceAfterLogin(force = recoveringSession)
        }
        _automaticLoginState.value = AutomaticLoginState.IDLE
        try {
            val entry = automaticStuEntry()
            val succeeded = awaitAutomaticLoginResult(
                awaitFreshAuthentication = {
                    sessionManager.awaitAuthenticationAfter(SchoolSystem.STU, authenticationVersion)
                },
                hasFreshAuthentication = ::freshlyAuthenticated,
                signIn = {
                    recoverAttendanceSession(::checkSession) {
                        recoveringSession = true
                        // Opening the hidden SSO chain uses cookies only, even when password login
                        // is disabled or paused. A real form separately asks for password permission.
                        entry != null && silentSchoolLogin.signIn(entry, savedLoginStore,
                            authorizeCredentials = {
                                deferredPasswordRetry.defer(savedLogin.value, timeProvider.epochMillis())
                                if (savedLogin.value.revision != revision ||
                                    !savedLogin.value.canAttemptAt(timeProvider.epochMillis())) false
                                else if (checkSession() != SessionState.EXPIRED) false
                                else if (savedLogin.value.revision != revision ||
                                    !savedLogin.value.canAttemptAt(timeProvider.epochMillis())) false
                                else {
                                    deferredPasswordRetry.clear()
                                    sessionManager.beginLogin(SchoolSystem.STU)
                                    usingCredentials = true
                                    _automaticLoginState.value = AutomaticLoginState.RUNNING
                                    true
                                }
                            },
                            onManualRequired = { manualRequired = true },
                            confirmSession = { checkSession() == SessionState.AUTHENTICATED },
                            expectedRevision = revision)
                    }
                }
            )
            if (!succeeded && !freshlyAuthenticated() && manualRequired && status.saved && status.enabled && !status.paused) {
                savedLoginStore.pauseAutomaticLogin(expectedRevision = revision)
            }
            // A concurrent protected response may succeed while the pause is being written.
            if (succeeded || freshlyAuthenticated()) completed()
            else _automaticLoginState.value = feedback()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.w("attendance session recovery failed: ${error.javaClass.simpleName}")
            if (freshlyAuthenticated()) {
                deferredPasswordRetry.clear()
                _automaticLoginState.value = if (usingCredentials) AutomaticLoginState.SUCCEEDED else AutomaticLoginState.IDLE
                refreshAttendanceAfterLogin(force = false)
            } else _automaticLoginState.value = feedback()
        } finally {
            if (_automaticLoginState.value == AutomaticLoginState.RUNNING) {
                _automaticLoginState.value = AutomaticLoginState.IDLE
            }
        }
    }

    private suspend fun refreshAttendanceAfterLogin(force: Boolean = true) {
        val version = sessionManager.accountVersion(SchoolSystem.STU)
        val now = timeProvider.nowDateTime()
        val date = attendanceDate(now)
        if (!attendanceRefreshGate.claim(version, date, timeProvider.epochMillis(), force)) return
        sessionManager.requireAccountHash(SchoolSystem.STU)
        attendanceRepository.refreshCurrentAttendance(now, AttendanceRefreshOrigin.SESSION_RECOVERY)
        // Keep this refresh inside the active recovery and consume its own final state before the
        // asynchronous UI notification. A failing punch endpoint must not restart this same flow.
        sessionRecovery.update(automaticForegroundAvailable.value, savedLogin.value.revision,
            sessionManager.current().stu)
    }

    private suspend fun verifyAttendanceSession(): SessionState {
        val before = sessionManager.authenticationVersion(SchoolSystem.STU)
        val accountBefore = sessionManager.accountVersion(SchoolSystem.STU)
        val checked = attendanceSessionVerifier.checkIdentity()
        val result = checked.state
        if (result == SessionState.AUTHENTICATED) {
            return sessionManager.withAccount(SchoolSystem.STU, checked.accountHash, accountBefore) {
                sessionManager.set(SchoolSystem.STU, SessionState.AUTHENTICATED)
                SessionState.AUTHENTICATED
            } ?: SessionState.ERROR
        }
        if (result == SessionState.EXPIRED && sessionManager.authenticationVersion(SchoolSystem.STU) == before) {
            if (!sessionManager.set(SchoolSystem.STU, result, accountBefore, before)) return SessionState.ERROR
        }
        return result
    }

    fun beginSchoolCookieNavigation(system: SchoolSystem?, onInvalidated: () -> Unit): AutoCloseable =
        cookieBridge.beginBrowserNavigation(system?.host, onInvalidated)

    suspend fun automaticStuEntry(): String? {
        val urls = urlProvider.current()
        // Endpoint overrides do not grant permission to share saved credentials elsewhere.
        return urls.stuEntry.takeIf { it == "https://stu.slai.edu.cn/sso/login" &&
            urls.stuBase == "https://stu.slai.edu.cn" }
    }

    fun beginManualLogin(system: SchoolSystem?, flowId: String) {
        updateWebLoginFlow(flowId)
        if (system == null) return
        // Stop old Chromium requests synchronously before the new visible WebView is created.
        cookieBridge.cancelBrowserNavigations(system.host)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { sessionManager.beginLogin(system) }
    }

    /**
     * 规范化后的教务系统地址：站点 / 应用根（`/yjsxt`）/ SSO 入口。
     *
     * WebView 需要它来判断「学校是不是把用户丢到了正方自带的账号密码页上」——那是一条死胡同，
     * 得改走 SSO 入口，否则首次登录的用户会卡在一个永远登不进去的表单上（见 [SisEndpoints]）。
     */
    val sisEndpoints: StateFlow<SisEndpoints> = urlProvider.urls
        .map { it.sisEndpoints }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SisEndpoints.of(null))

    /** Where the capture tool starts from (honours a user override of the base URL). */
    val sisEntryUrl: StateFlow<String> = sisEndpoints
        .map { it.entry }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SisEndpoints.of(null).entry)

    private val webLoginCompletion = WebLoginCompletion()
    val webCompleting: StateFlow<Boolean> = webLoginCompletion.isCompleting

    fun updateWebLoginFlow(flowId: String?) {
        webLoginCompletion.update(flowId)
    }

    /**
     * 登录**确认成功**（探活通过）时发一次事件，让 WebView 自己退场。
     *
     * 用事件而不是状态：这是"刚刚发生了一次"，不是"当前处于某种状态"，
     * 重新收集不应该再关一次页面。
     */
    private val _loginCompleted = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val loginCompleted: SharedFlow<String> = _loginCompleted.asSharedFlow()

    init {
        viewModelScope.launch {
            // A second real request can fail while the persisted EXPIRED enum stays unchanged.
            // Listen to complete refresh results as well as state transitions so that event is kept.
            combine(attendanceRepository.refreshUpdates, sessionManager.state) { updates, session ->
                updates.values.filter {
                    it.accountHash == session.stuAccountHash &&
                        it.accountVersion == sessionManager.accountVersion(SchoolSystem.STU)
                }.maxByOrNull { it.sequence }
            }.distinctUntilChangedBy { it?.sequence }.collect { update ->
                if (update?.origin == AttendanceRefreshOrigin.USER &&
                    update.result is AttendanceRefreshResult.SessionExpired) sessionRecovery.requestCheck()
            }
        }
        viewModelScope.launch {
            automaticLoginWakeups(
                automaticForegroundAvailable, savedLogin, networkMonitor.networkChanges, timeProvider::epochMillis
            ).collect { event ->
                val status = savedLogin.value
                val now = timeProvider.epochMillis()
                if (!automaticForegroundAvailable.value) return@collect
                if (event == AutomaticLoginWakeup.COOLDOWN_FINISHED &&
                    !deferredPasswordRetry.consume(status, now)) return@collect
                sessionRecovery.requestCheck()
            }
        }
        viewModelScope.launch {
            // 应用内更新：注册每日检查，并在启动时做一次"超过一天没查过才真查"的静默检查。
            com.slai.campus.worker.UpdateCheckWorker.enqueuePeriodic(appContext)
            runCatching { updateRepository.checkIfStale() }
                .onFailure { AppLog.w("update check failed: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * Called by the WebView overlay after every page load.
     *
     * When the user lands back inside a protected business page, the login flow has completed: probe
     * the session, and on success refresh data for that system.
     * This closes the loop the plan describes in §15.
     */
    fun onWebPageFinished(url: String, isLoginFlow: Boolean, flowId: String) {
        if (!isLoginFlow) return
        val landed = businessLanding(url) ?: return

        webLoginCompletion.start(flowId, viewModelScope) finish@{
            // Android holds cookies in memory until flush(); without this the session the user
            // just established can vanish if the app is killed, and the timetable silently
            // starts answering 901 again.
            cookieBridge.flush()
            val state = if (landed == SchoolSystem.STU) verifyAttendanceSession()
                else sessionManager.probe(landed, requireFresh = true)
            if (!webLoginCompletion.isCurrent(flowId)) return@finish
            AppLog.i("login flow finished for $landed: $state")
            if (state == SessionState.AUTHENTICATED) {
                // Only a student-system sign-in recovers its paused automatic login.
                savedLoginStore.loginSucceeded(landed)
                if (!webLoginCompletion.isCurrent(flowId)) return@finish
                // 先让 WebView 退场，再刷新对应系统的数据，
                // 不必等这一轮请求跑完（那可能要好几秒）。
                webLoginCompletion.markCompleted(flowId)
                _loginCompleted.tryEmit(flowId)
                // Closing the successful login page cancels only its probe, not this refresh.
                viewModelScope.launch {
                    sessionManager.requireAccountHash(landed)
                    when (landed) {
                        SchoolSystem.SIS -> scheduleRepository.refresh(RefreshReason.AFTER_LOGIN)
                        SchoolSystem.STU -> refreshAttendanceAfterLogin()
                    }
                }
            }
        }
    }

    /**
     * Hands a finished capture to the provider screen, which owns the analysis and the result log.
     */
    fun onCaptureFinished(session: CaptureSession) {
        AppLog.i("capture finished: ${session.records.size} xhr, ${session.requests.size} request(s)")
        captureBus.publish(session)
    }

    /**
     * True when [url] is a protected page of a school system rather than the SSO/login page.
     *
     * The AD FS chain bounces between `sts.slai.edu.cn` and the business host, so the check has to be
     * host *and* path based: `sis.slai.edu.cn/yjsxt/htxylogin` is still the login endpoint, while
     * `sis.slai.edu.cn/yjsxt/xtgl/index_initMenu.html` means we are in.
     */
    private fun businessLanding(url: String): SchoolSystem? {
        val value = url.lowercase()
        val isLoginPage = value.contains("login_slogin") ||
            value.contains("/htxylogin") ||
            value.contains("/a/login") ||
            value.contains("/sso/") ||
            value.contains("sts.slai.edu.cn")
        if (isLoginPage) return null

        return when {
            value.contains("sis.slai.edu.cn/yjsxt") -> SchoolSystem.SIS
            value.contains("stu.slai.edu.cn") -> SchoolSystem.STU
            else -> null
        }
    }
}
