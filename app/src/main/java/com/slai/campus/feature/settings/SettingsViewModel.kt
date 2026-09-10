package com.slai.campus.feature.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.core.session.ReminderConfig
import com.slai.campus.core.session.SessionManager
import com.slai.campus.core.session.SessionSnapshot
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.session.SessionStore
import com.slai.campus.core.session.WebCookieBridge
import com.slai.campus.core.web.AppUrlProvider
import com.slai.campus.core.web.AppUrls
import com.slai.campus.data.sis.SisConfig
import com.slai.campus.data.stu.StuConfig
import com.slai.campus.reminder.NotificationHelper
import com.slai.campus.reminder.ReminderScheduler
import com.slai.campus.worker.ScheduleSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class SettingsUiState(
    val session: SessionSnapshot = SessionSnapshot(),
    val urls: AppUrls? = null,
    val reminders: ReminderConfig = ReminderConfig(),
    val firstWeekMonday: LocalDate? = null,
    val anchorConfirmed: Boolean = false,
    val studentIdHint: String? = null,
    val exactAlarmAllowed: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val language: com.slai.campus.core.common.AppLanguage = com.slai.campus.core.common.AppLanguage.SYSTEM,
    val theme: com.slai.campus.core.common.AppTheme = com.slai.campus.core.common.AppTheme.SYSTEM,
    /** 首页是否显示课表区块。默认显示。 */
    val showTimetableOnHome: Boolean = true
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val sessionStore: SessionStore,
    private val urlProvider: AppUrlProvider,
    private val cookieBridge: WebCookieBridge,
    private val reminderScheduler: ReminderScheduler,
    private val timeProvider: TimeProvider,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private data class Core(
        val session: com.slai.campus.core.session.SessionSnapshot,
        val urls: AppUrls?,
        val reminders: ReminderConfig,
        val schedulePrefs: SchedulePrefs
    )

    /**
     * 课表相关的偏好（学期锚点、学号提示）。
     *
     * 这里以前是一个 `MutableStateFlow<Extras>` + `refreshExtras()`「写完手动回读」的组合 ——
     * UDF 的环没有闭合：任何**不经过本 ViewModel** 的写入（后台刷新的学期自动发现）都不会回推，
     * 界面就停在旧值上。改成直接 observe Store 的 Flow 之后，extras 和 refreshExtras() 一起消失。
     */
    private data class SchedulePrefs(
        val firstWeekMonday: LocalDate? = null,
        val anchorConfirmed: Boolean = false,
        val studentIdHint: String? = null
    )

    private val schedulePrefs = combine(
        sessionStore.semesterAnchorFlow,
        sessionStore.studentIdHintFlow
    ) { (monday, confirmed), hint ->
        SchedulePrefs(firstWeekMonday = monday, anchorConfirmed = confirmed, studentIdHint = hint)
    }

    // combine() 只有到 5 个 flow 的重载，第 5 个（语言）所以再套一层。
    private val core = combine(
        sessionManager.state,
        urlProvider.urls,
        sessionStore.reminderConfig,
        schedulePrefs
    ) { session, urls, reminders, prefs -> Core(session, urls, reminders, prefs) }

    private data class UiPrefs(
        val language: com.slai.campus.core.common.AppLanguage,
        val theme: com.slai.campus.core.common.AppTheme,
        val showTimetableOnHome: Boolean
    )

    // combine() 只到 5 个 flow，所以这几项"改了就立刻生效"的纯界面偏好先自己合成一个。
    private val uiPrefs = combine(
        sessionStore.appLanguage,
        sessionStore.appTheme,
        sessionStore.showTimetableOnHome
    ) { language, theme, showTimetable ->
        UiPrefs(language, theme, showTimetable)
    }

    val state: StateFlow<SettingsUiState> = combine(
        core,
        uiPrefs
    ) { c, prefs ->
        SettingsUiState(
            session = c.session,
            urls = c.urls,
            reminders = c.reminders,
            firstWeekMonday = c.schedulePrefs.firstWeekMonday,
            anchorConfirmed = c.schedulePrefs.anchorConfirmed,
            studentIdHint = c.schedulePrefs.studentIdHint,
            exactAlarmAllowed = reminderScheduler.canScheduleExactAlarms(),
            notificationsAllowed = NotificationHelper.canPostNotifications(appContext),
            language = prefs.language,
            theme = prefs.theme,
            showTimetableOnHome = prefs.showTimetableOnHome
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** 切换界面语言：写进 DataStore，Compose 侧立刻重组，不需要重启 Activity。 */
    fun setLanguage(language: com.slai.campus.core.common.AppLanguage) {
        viewModelScope.launch { sessionStore.setAppLanguage(language) }
    }

    /** 切换深浅色，同样即时生效。 */
    fun setTheme(theme: com.slai.campus.core.common.AppTheme) {
        viewModelScope.launch { sessionStore.setAppTheme(theme) }
    }

    /** 首页要不要显示课表。关掉后首页只剩考勤（高年级没课时用）。 */
    fun setShowTimetableOnHome(show: Boolean) {
        viewModelScope.launch { sessionStore.setShowTimetableOnHome(show) }
    }

    // ---- session ---------------------------------------------------------------------------

    fun clearSession(system: SchoolSystem) {
        viewModelScope.launch {
            sessionManager.signOut(system, cookieBridge)
            if (system == SchoolSystem.SIS) {
                ScheduleSyncWorker.cancelAll(appContext)
            }
        }
    }

    fun probeSession(system: SchoolSystem) {
        viewModelScope.launch { sessionManager.probe(system) }
    }

    // ---- semester anchor -------------------------------------------------------------------

    /** Sets the Monday of teaching week 1 and marks it as user-confirmed (highest evidence). */
    fun setFirstWeekMonday(monday: LocalDate?) {
        viewModelScope.launch {
            val normalized = monday?.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            sessionStore.setSemesterAnchor(normalized, confirmed = normalized != null)
            // 不需要手动刷新界面状态：schedulePrefs 直接在 observe Store 的 Flow。
            reminderScheduler.rescheduleFromCache()
        }
    }

    /** Quick action: assume the current week is week [weekNumber]. */
    fun setFromCurrentWeek(weekNumber: Int) {
        val monday = timeProvider.today()
            .with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .minusWeeks((weekNumber - 1).toLong())
        setFirstWeekMonday(monday)
    }

    // ---- identity --------------------------------------------------------------------------

    fun setStudentIdHint(value: String?) {
        viewModelScope.launch {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
            sessionStore.setStudentIdHint(trimmed)
            if (trimmed != null) {
                sessionManager.setAccountHash(com.slai.campus.core.session.AccountHasher.hash(trimmed))
            }
        }
    }

    // ---- reminders -------------------------------------------------------------------------

    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            sessionStore.setRemindersEnabled(enabled)
            if (enabled) {
                reminderScheduler.rescheduleFromCache()
                ScheduleSyncWorker.enqueuePeriodic(appContext)
            } else {
                reminderScheduler.cancelAll()
            }
        }
    }

    fun setReminderLeadMinutes(minutes: Int) {
        viewModelScope.launch {
            sessionStore.setReminderLeadMinutes(minutes)
            reminderScheduler.rescheduleFromCache()
        }
    }

    fun setReminderExact(exact: Boolean) {
        viewModelScope.launch {
            sessionStore.setReminderExact(exact)
            reminderScheduler.rescheduleFromCache()
        }
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            appContext.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ---- endpoints -------------------------------------------------------------------------

    fun setSisBaseUrl(value: String) {
        viewModelScope.launch {
            sessionStore.setBaseUrl(SchoolSystem.SIS, value.trim())
        }
    }

    fun setStuBaseUrl(value: String) {
        viewModelScope.launch {
            sessionStore.setBaseUrl(SchoolSystem.STU, value.trim())
        }
    }

    fun resetEndpoints() {
        viewModelScope.launch {
            sessionStore.setBaseUrl(SchoolSystem.SIS, SisConfig.defaultBaseUrl)
            sessionStore.setBaseUrl(SchoolSystem.STU, StuConfig.defaultBaseUrl)
        }
    }

    fun sessionLabel(state: SessionState): String = when (state) {
        SessionState.UNKNOWN -> "未检测"
        SessionState.AUTHENTICATING -> "登录中"
        SessionState.AUTHENTICATED -> "已登录"
        SessionState.EXPIRED -> "已过期"
        SessionState.NEEDS_LOGIN -> "需要登录"
        SessionState.ERROR -> "无法检测"
    }
}
