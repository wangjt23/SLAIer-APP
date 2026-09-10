package com.slai.campus.core.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.slai.campus.core.common.SchoolSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/**
 * Persists the *derived* authentication state and the user's configuration.
 *
 * Deliberately does not persist cookies, tokens or passwords: cookies live in the WebView's own
 * store (and are excluded from backup), and everything here is non-secret bookkeeping.
 */
@Singleton
class SessionStore @Inject constructor(
    private val context: Context
) {

    private object Keys {
        val SIS_STATE = stringPreferencesKey("sis_state")
        val STU_STATE = stringPreferencesKey("stu_state")
        val SIS_LAST_PROBE = longPreferencesKey("sis_last_probe")
        val STU_LAST_PROBE = longPreferencesKey("stu_last_probe")

        val ACCOUNT_HASH = stringPreferencesKey("account_hash")
        val STUDENT_ID_HINT = stringPreferencesKey("student_id_hint")

        val FIRST_WEEK_MONDAY = longPreferencesKey("first_week_monday_epoch_day")
        val FIRST_WEEK_ANCHOR_CONFIRMED = booleanPreferencesKey("first_week_anchor_confirmed")
        val SEMESTER_YEAR = stringPreferencesKey("semester_year")
        val SEMESTER_TERM = stringPreferencesKey("semester_term")

        val SIS_BASE_URL = stringPreferencesKey("sis_base_url")
        val STU_BASE_URL = stringPreferencesKey("stu_base_url")

        val REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        val REMINDER_LEAD_MINUTES = intPreferencesKey("reminder_lead_minutes")
        val REMINDER_EXACT = booleanPreferencesKey("reminder_exact")
        val REMINDER_WINDOW_DAYS = intPreferencesKey("reminder_window_days")

        val SIS_MODE = stringPreferencesKey("sis_integration_mode")
        val STU_MODE = stringPreferencesKey("stu_integration_mode")

        val ATTENDANCE_GOAL_MINUTES = intPreferencesKey("attendance_goal_minutes")

        /** 界面语言（system / zh / en）。默认跟随系统。 */
        val APP_LANGUAGE = stringPreferencesKey("app_language")

        /** 深浅色（system / light / dark）。默认跟随系统。 */
        val APP_THEME = stringPreferencesKey("app_theme")

        /**
         * 首页是否显示课表区块。默认显示。
         *
         * 课表不是所有人都需要：博二、博三基本没课，首页全是"暂无课表数据"反而是噪音，
         * 关掉之后首页只剩考勤。
         */
        val SHOW_TIMETABLE_ON_HOME = booleanPreferencesKey("show_timetable_on_home")

        /** Occurrence ids that currently have a scheduled alarm, so stale ones can be cancelled. */
        val SCHEDULED_REMINDERS = androidx.datastore.preferences.core.stringSetPreferencesKey("scheduled_reminders")
    }

    val snapshot: Flow<SessionSnapshot> = context.sessionDataStore.data.map { prefs ->
        SessionSnapshot(
            sis = prefs[Keys.SIS_STATE]?.let { runCatching { SessionState.valueOf(it) }.getOrNull() }
                ?: SessionState.UNKNOWN,
            stu = prefs[Keys.STU_STATE]?.let { runCatching { SessionState.valueOf(it) }.getOrNull() }
                ?: SessionState.UNKNOWN,
            accountHash = prefs[Keys.ACCOUNT_HASH]
        )
    }

    suspend fun current(): SessionSnapshot = snapshot.first()

    suspend fun setState(system: SchoolSystem, state: SessionState) {
        context.sessionDataStore.edit { prefs ->
            when (system) {
                SchoolSystem.SIS -> {
                    prefs[Keys.SIS_STATE] = state.name
                    prefs[Keys.SIS_LAST_PROBE] = System.currentTimeMillis()
                }
                SchoolSystem.STU -> {
                    prefs[Keys.STU_STATE] = state.name
                    prefs[Keys.STU_LAST_PROBE] = System.currentTimeMillis()
                }
            }
        }
    }

    suspend fun setAccountHash(hash: String?) {
        context.sessionDataStore.edit { prefs ->
            if (hash.isNullOrBlank()) prefs.remove(Keys.ACCOUNT_HASH) else prefs[Keys.ACCOUNT_HASH] = hash
        }
    }

    suspend fun setStudentIdHint(hint: String?) {
        context.sessionDataStore.edit { prefs ->
            if (hint.isNullOrBlank()) prefs.remove(Keys.STUDENT_ID_HINT) else prefs[Keys.STUDENT_ID_HINT] = hint
        }
    }

    suspend fun studentIdHint(): String? = context.sessionDataStore.data.first()[Keys.STUDENT_ID_HINT]

    suspend fun setSemesterAnchor(monday: LocalDate?, confirmed: Boolean) {
        context.sessionDataStore.edit { prefs ->
            if (monday == null) {
                prefs.remove(Keys.FIRST_WEEK_MONDAY)
                prefs[Keys.FIRST_WEEK_ANCHOR_CONFIRMED] = false
            } else {
                prefs[Keys.FIRST_WEEK_MONDAY] = monday.toEpochDay()
                prefs[Keys.FIRST_WEEK_ANCHOR_CONFIRMED] = confirmed
            }
        }
    }

    suspend fun semesterAnchor(): Pair<LocalDate?, Boolean> {
        val prefs = context.sessionDataStore.data.first()
        val day = prefs[Keys.FIRST_WEEK_MONDAY]
        return (day?.let { LocalDate.ofEpochDay(it) }) to (prefs[Keys.FIRST_WEEK_ANCHOR_CONFIRMED] ?: false)
    }

    /** 界面语言，热流形式，切换后界面立刻跟着变。 */
    val appLanguage: Flow<com.slai.campus.core.common.AppLanguage> =
        context.sessionDataStore.data.map { prefs ->
            com.slai.campus.core.common.AppLanguage.fromStored(prefs[Keys.APP_LANGUAGE])
        }

    suspend fun setAppLanguage(language: com.slai.campus.core.common.AppLanguage) {
        context.sessionDataStore.edit { it[Keys.APP_LANGUAGE] = language.storedValue }
    }

    /** 深浅色，同样做成热流，切换后界面立刻跟着变（不重建 Activity）。 */
    val appTheme: Flow<com.slai.campus.core.common.AppTheme> =
        context.sessionDataStore.data.map { prefs ->
            com.slai.campus.core.common.AppTheme.fromStored(prefs[Keys.APP_THEME])
        }

    suspend fun setAppTheme(theme: com.slai.campus.core.common.AppTheme) {
        context.sessionDataStore.edit { it[Keys.APP_THEME] = theme.storedValue }
    }

    suspend fun setSemesterCodes(year: String?, term: String?) {
        context.sessionDataStore.edit { prefs ->
            if (year.isNullOrBlank()) prefs.remove(Keys.SEMESTER_YEAR) else prefs[Keys.SEMESTER_YEAR] = year
            if (term.isNullOrBlank()) prefs.remove(Keys.SEMESTER_TERM) else prefs[Keys.SEMESTER_TERM] = term
        }
    }

    suspend fun semesterCodes(): Pair<String?, String?> {
        val prefs = context.sessionDataStore.data.first()
        return prefs[Keys.SEMESTER_YEAR] to prefs[Keys.SEMESTER_TERM]
    }

    suspend fun setBaseUrl(system: SchoolSystem, url: String) {
        context.sessionDataStore.edit { prefs ->
            when (system) {
                SchoolSystem.SIS -> prefs[Keys.SIS_BASE_URL] = url
                SchoolSystem.STU -> prefs[Keys.STU_BASE_URL] = url
            }
        }
    }

    suspend fun baseUrl(system: SchoolSystem): String? {
        val prefs = context.sessionDataStore.data.first()
        return when (system) {
            SchoolSystem.SIS -> prefs[Keys.SIS_BASE_URL]
            SchoolSystem.STU -> prefs[Keys.STU_BASE_URL]
        }
    }

    /** (sisBase, stuBase), either of which may be null when the user has not overridden it. */
    val baseUrls: Flow<Pair<String?, String?>> = context.sessionDataStore.data.map { prefs ->
        prefs[Keys.SIS_BASE_URL] to prefs[Keys.STU_BASE_URL]
    }

    /**
     * 首页要不要显示课表。默认 true —— 关闭是「我确实不需要课表」的显式选择，
     * 所以没有值时不能猜成 false。
     */
    val showTimetableOnHome: Flow<Boolean> = context.sessionDataStore.data.map { prefs ->
        prefs[Keys.SHOW_TIMETABLE_ON_HOME] ?: true
    }

    suspend fun setShowTimetableOnHome(show: Boolean) {
        context.sessionDataStore.edit { it[Keys.SHOW_TIMETABLE_ON_HOME] = show }
    }

    val reminderConfig: Flow<ReminderConfig> = context.sessionDataStore.data.map { prefs ->
        ReminderConfig(
            enabled = prefs[Keys.REMINDERS_ENABLED] ?: true,
            leadMinutes = prefs[Keys.REMINDER_LEAD_MINUTES] ?: 15,
            exact = prefs[Keys.REMINDER_EXACT] ?: false,
            windowDays = prefs[Keys.REMINDER_WINDOW_DAYS] ?: 14
        )
    }

    suspend fun reminderConfigOnce(): ReminderConfig = reminderConfig.first()

    suspend fun setRemindersEnabled(enabled: Boolean) =
        context.sessionDataStore.edit { it[Keys.REMINDERS_ENABLED] = enabled }

    suspend fun setReminderLeadMinutes(minutes: Int) =
        context.sessionDataStore.edit { it[Keys.REMINDER_LEAD_MINUTES] = minutes.coerceIn(1, 120) }

    suspend fun setReminderExact(exact: Boolean) =
        context.sessionDataStore.edit { it[Keys.REMINDER_EXACT] = exact }

    suspend fun setIntegrationMode(system: SchoolSystem, mode: com.slai.campus.core.common.IntegrationMode) {
        context.sessionDataStore.edit { prefs ->
            when (system) {
                SchoolSystem.SIS -> prefs[Keys.SIS_MODE] = mode.name
                SchoolSystem.STU -> prefs[Keys.STU_MODE] = mode.name
            }
        }
    }

    suspend fun integrationMode(system: SchoolSystem): com.slai.campus.core.common.IntegrationMode? {
        val prefs = context.sessionDataStore.data.first()
        val raw = when (system) {
            SchoolSystem.SIS -> prefs[Keys.SIS_MODE]
            SchoolSystem.STU -> prefs[Keys.STU_MODE]
        }
        return raw?.let { runCatching { com.slai.campus.core.common.IntegrationMode.valueOf(it) }.getOrNull() }
    }

    /** Daily attendance target in minutes. The college requires 6 hours on working days. */
    val attendanceGoalMinutes: Flow<Int> = context.sessionDataStore.data.map { it[Keys.ATTENDANCE_GOAL_MINUTES] ?: 360 }

    suspend fun attendanceGoalMinutesOnce(): Int = attendanceGoalMinutes.first()

    suspend fun setAttendanceGoalMinutes(minutes: Int) =
        context.sessionDataStore.edit { it[Keys.ATTENDANCE_GOAL_MINUTES] = minutes.coerceIn(30, 24 * 60) }

    suspend fun scheduledReminderIds(): Set<String> =
        context.sessionDataStore.data.first()[Keys.SCHEDULED_REMINDERS] ?: emptySet()

    suspend fun setScheduledReminderIds(ids: Set<String>) {
        context.sessionDataStore.edit { prefs -> prefs[Keys.SCHEDULED_REMINDERS] = ids }
    }
}

data class ReminderConfig(
    val enabled: Boolean = true,
    val leadMinutes: Int = 15,
    val exact: Boolean = false,
    val windowDays: Int = 14
)
