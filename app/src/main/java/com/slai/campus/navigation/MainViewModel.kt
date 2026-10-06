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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import com.slai.campus.core.web.AutomaticLoginState
import com.slai.campus.core.web.SilentSchoolLogin
import com.slai.campus.core.session.awaitAutomaticLoginResult
import com.slai.campus.core.session.recoverAttendanceSession
import com.slai.campus.core.session.AutomaticLoginTrigger
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
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    val session: StateFlow<SessionSnapshot> = sessionManager.state
    val savedLogin = savedLoginStore.status
    private var automaticLoginRevision: Long? = null
    private val automaticLoginTrigger = AutomaticLoginTrigger()
    private var automaticLoginJob: Job? = null
    private val _automaticLoginState = MutableStateFlow(AutomaticLoginState.IDLE)
    val automaticLoginState = _automaticLoginState.asStateFlow()

    fun stopAutomaticLogin() {
        automaticLoginTrigger.update(false, savedLogin.value.revision)
        automaticLoginJob?.cancel()
        if (_automaticLoginState.value == AutomaticLoginState.RUNNING) {
            _automaticLoginState.value = AutomaticLoginState.IDLE
        }
    }

    /** Called by the foreground root; this never creates a visible navigation overlay. */
    fun updateAutomaticLogin(foregroundAvailable: Boolean) {
        val status = savedLogin.value
        automaticLoginTrigger.update(foregroundAvailable && status.enabled && status.saved, status.revision)
        if (!foregroundAvailable || !status.enabled || !status.saved) {
            stopAutomaticLogin()
            _automaticLoginState.value = AutomaticLoginState.IDLE
            return
        }
        if (automaticLoginJob?.isCompleted == false) {
            if (automaticLoginRevision != status.revision) automaticLoginJob?.cancel()
            return
        }
        if (!automaticLoginTrigger.claim(session.value.stu, System.currentTimeMillis())) return
        automaticLoginRevision = status.revision
        automaticLoginJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val authenticationVersion = sessionManager.authenticationVersion(SchoolSystem.STU)
            fun freshlyAuthenticated() = sessionManager.authenticationVersion(SchoolSystem.STU) > authenticationVersion
            var usingCredentials = false
            var recoveringSession = false
            var manualRequired = false
            _automaticLoginState.value = AutomaticLoginState.IDLE
            try {
                val entry = automaticStuEntry()
                val succeeded = entry != null && awaitAutomaticLoginResult(
                    awaitFreshAuthentication = {
                        sessionManager.awaitAuthenticationAfter(SchoolSystem.STU, authenticationVersion)
                    },
                    hasFreshAuthentication = ::freshlyAuthenticated,
                    signIn = {
                        recoverAttendanceSession(::verifyAttendanceSession) {
                            recoveringSession = true
                            silentSchoolLogin.signIn(entry, savedLoginStore,
                                authorizeCredentials = {
                                    // An actual login form appeared after cookie-only SSO recovery.
                                    // Check again before sending credentials or showing a login notice.
                                    if (verifyAttendanceSession() != SessionState.EXPIRED) false
                                    else if (!savedLogin.value.canAttempt) false
                                    else {
                                        usingCredentials = true
                                        _automaticLoginState.value = AutomaticLoginState.RUNNING
                                        true
                                    }
                                },
                                onManualRequired = { manualRequired = true },
                                confirmSession = { verifyAttendanceSession() == SessionState.AUTHENTICATED })
                        }
                    }
                )
                if (!succeeded && !freshlyAuthenticated() && manualRequired) {
                    savedLoginStore.pauseAutomaticLogin()
                }
                // Recheck after the disk write: a concurrent attendance response may have just succeeded.
                if (succeeded || freshlyAuthenticated()) {
                    cookieBridge.flush()
                    savedLoginStore.loginSucceeded()
                    automaticLoginRevision = null
                    _automaticLoginState.value = if (usingCredentials) AutomaticLoginState.SUCCEEDED else AutomaticLoginState.IDLE
                    if (recoveringSession) viewModelScope.launch { refreshAttendanceAfterLogin() }
                } else {
                    _automaticLoginState.value = if (usingCredentials || manualRequired) AutomaticLoginState.FAILED else AutomaticLoginState.IDLE
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.w("automatic login failed: ${error.javaClass.simpleName}")
                if (freshlyAuthenticated()) {
                    runCatching { savedLoginStore.loginSucceeded() }
                    automaticLoginRevision = null
                    _automaticLoginState.value = if (usingCredentials) AutomaticLoginState.SUCCEEDED else AutomaticLoginState.IDLE
                } else {
                    if (manualRequired) runCatching { savedLoginStore.pauseAutomaticLogin() }
                    _automaticLoginState.value = if (usingCredentials || manualRequired) AutomaticLoginState.FAILED else AutomaticLoginState.IDLE
                }
            } finally {
                if (_automaticLoginState.value == AutomaticLoginState.RUNNING) {
                    _automaticLoginState.value = AutomaticLoginState.IDLE
                }
                automaticLoginJob = null
                automaticLoginRevision = null
                automaticLoginTrigger.finish()
                // A foreground entry or settings change may have arrived during cancellation.
                updateAutomaticLogin(automaticLoginTrigger.available)
            }
        }
        automaticLoginJob?.start()
    }

    private suspend fun refreshAttendanceAfterLogin() {
        sessionManager.requireAccountHash()
        val today = timeProvider.today()
        attendanceRepository.refresh(com.slai.campus.data.stu.StuAttendanceDataSource.monthOf(today))
        val (from, to) = com.slai.campus.domain.attendance.monthPunchRange(java.time.YearMonth.from(today))
        attendanceRepository.refreshPunches(from, to)
    }

    private suspend fun verifyAttendanceSession(): SessionState {
        val before = sessionManager.authenticationVersion(SchoolSystem.STU)
        val result = attendanceSessionVerifier.check()
        if (result == SessionState.AUTHENTICATED ||
            (result == SessionState.EXPIRED && sessionManager.authenticationVersion(SchoolSystem.STU) == before)) {
            sessionManager.set(SchoolSystem.STU, result)
        }
        return result
    }

    suspend fun automaticStuEntry(): String? {
        val urls = urlProvider.current()
        // Endpoint overrides do not grant permission to share saved credentials elsewhere.
        return urls.stuEntry.takeIf { it == "https://stu.slai.edu.cn/sso/login" &&
            urls.stuBase == "https://stu.slai.edu.cn" }
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

    private val _webCompleting = MutableStateFlow(false)
    val webCompleting: StateFlow<Boolean> = _webCompleting.asStateFlow()

    /**
     * 登录**确认成功**（探活通过）时发一次事件，让 WebView 自己退场。
     *
     * 用事件而不是状态：这是"刚刚发生了一次"，不是"当前处于某种状态"，
     * 重新收集不应该再关一次页面。
     */
    private val _loginCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loginCompleted: SharedFlow<Unit> = _loginCompleted.asSharedFlow()

    init {
        viewModelScope.launch {
            // Cached timetable reads never need a live SIS session.
            runCatching { verifyAttendanceSession() }
                .onFailure { AppLog.w("startup probe failed: ${it.javaClass.simpleName}") }
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
    fun onWebPageFinished(url: String, isLoginFlow: Boolean) {
        if (!isLoginFlow) return
        val landed = businessLanding(url) ?: return
        if (_webCompleting.value) return

        viewModelScope.launch {
            _webCompleting.value = true
            try {
                // Android holds cookies in memory until flush(); without this the session the user
                // just established can vanish if the app is killed, and the timetable silently
                // starts answering 901 again.
                cookieBridge.flush()
                val state = if (landed == SchoolSystem.STU) verifyAttendanceSession()
                    else sessionManager.probe(landed, requireFresh = true)
                AppLog.i("login flow finished for $landed: $state")
                if (state == SessionState.AUTHENTICATED) {
                    // A successful explicit sign-in recovers a paused automatic login.
                    savedLoginStore.loginSucceeded()
                    automaticLoginRevision = null
                    // 先让 WebView 退场，再刷新对应系统的数据，
                    // 不必等这一轮请求跑完（那可能要好几秒）。
                    _loginCompleted.tryEmit(Unit)
                    sessionManager.requireAccountHash()
                    when (landed) {
                        SchoolSystem.SIS -> scheduleRepository.refresh(RefreshReason.AFTER_LOGIN)
                        SchoolSystem.STU -> refreshAttendanceAfterLogin()
                    }
                }
            } finally {
                _webCompleting.value = false
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
