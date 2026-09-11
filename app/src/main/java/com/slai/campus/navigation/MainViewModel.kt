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
import com.slai.campus.worker.ScheduleSyncWorker
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
import javax.inject.Inject

/**
 * Owns cross-screen state: the two session machines, the "did the login WebView finish?" handshake,
 * and app-start hydration.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val scheduleRepository: ScheduleRepository,
    private val urlProvider: AppUrlProvider,
    private val captureBus: CaptureBus,
    private val updateRepository: com.slai.campus.domain.update.UpdateRepository,
    private val cookieBridge: com.slai.campus.core.session.WebCookieBridge,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    val session: StateFlow<SessionSnapshot> = sessionManager.state

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
            // Probe silently on start: it tells the home screen whether to show the
            // "需要重新登录" banner without ever opening a login UI by itself.
            runCatching { sessionManager.probeAll() }
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
     * the session, and on success immediately refresh the timetable and enqueue a background sync.
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
                val state = sessionManager.probe(landed)
                AppLog.i("login flow finished for $landed: $state")
                if (state == SessionState.AUTHENTICATED) {
                    // 先让 WebView 退场，再刷新课表：用户马上回到 App 并看到"正在同步"，
                    // 不必等这一轮请求跑完（那可能要好几秒）。
                    _loginCompleted.tryEmit(Unit)
                    sessionManager.requireAccountHash()
                    scheduleRepository.refresh(RefreshReason.AFTER_LOGIN)
                    ScheduleSyncWorker.enqueuePeriodic(appContext)
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
