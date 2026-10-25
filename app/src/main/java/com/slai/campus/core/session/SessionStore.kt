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
class SessionStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject constructor(context: Context) : this(context.sessionDataStore)

    private object Keys {
        val SIS_STATE = stringPreferencesKey("sis_state")
        val STU_STATE = stringPreferencesKey("stu_state")
        val SIS_LAST_PROBE = longPreferencesKey("sis_last_probe")
        val STU_LAST_PROBE = longPreferencesKey("stu_last_probe")

        val ACCOUNT_HASH = stringPreferencesKey("account_hash")
        val SIS_ACCOUNT_HASH = stringPreferencesKey("sis_account_hash")
        val STU_ACCOUNT_HASH = stringPreferencesKey("stu_account_hash")
        val SIS_ACCOUNT_PENDING = booleanPreferencesKey("sis_account_pending")
        val STU_ACCOUNT_PENDING = booleanPreferencesKey("stu_account_pending")
        val STUDENT_ID_HINT = stringPreferencesKey("student_id_hint")

        val FIRST_WEEK_MONDAY = longPreferencesKey("first_week_monday_epoch_day")
        val FIRST_WEEK_ANCHOR_CONFIRMED = booleanPreferencesKey("first_week_anchor_confirmed")
        val SEMESTER_YEAR = stringPreferencesKey("semester_year")
        val SEMESTER_TERM = stringPreferencesKey("semester_term")
        val ANCHOR_YEAR = stringPreferencesKey("semester_anchor_year")
        val ANCHOR_TERM = stringPreferencesKey("semester_anchor_term")
        val SEMESTER_VERIFIED_AT = longPreferencesKey("semester_verified_at")

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

        /** 自动检查更新（默认开）；关掉后只有手动点「检查更新」才会请求 GitHub。 */
        val UPDATE_CHECK_ENABLED = booleanPreferencesKey("update_check_enabled")

        /** 用户点了「忽略此版本」的那个版本号，出现更高版本时会重新提示。 */
        val UPDATE_IGNORED_VERSION = stringPreferencesKey("update_ignored_version")

        /** 上次检查更新的时间戳；静默检查按它判断"是不是已经超过一天"。 */
        val UPDATE_LAST_CHECK_AT = longPreferencesKey("update_last_check_at")

        /** GitHub API 的 ETag，命中 304 时不用重新解析整个 payload。 */
        val UPDATE_ETAG = stringPreferencesKey("update_etag")
        val UPDATE_CACHED_PAYLOAD = stringPreferencesKey("update_cached_payload")
        val UPDATE_CACHED_SOURCE = stringPreferencesKey("update_cached_source")
        val UPDATE_LAST_ATTEMPT_AT = longPreferencesKey("update_last_attempt_at")

        /** Occurrence ids that currently have a scheduled alarm, so stale ones can be cancelled. */
        val SCHEDULED_REMINDERS = androidx.datastore.preferences.core.stringSetPreferencesKey("scheduled_reminders")
    }

    val snapshot: Flow<SessionSnapshot> = dataStore.data.map { prefs ->
        SessionSnapshot(
            sis = prefs[Keys.SIS_STATE]?.let { runCatching { SessionState.valueOf(it) }.getOrNull() }
                ?: SessionState.UNKNOWN,
            stu = prefs[Keys.STU_STATE]?.let { runCatching { SessionState.valueOf(it) }.getOrNull() }
                ?: SessionState.UNKNOWN,
            // The legacy partition belongs to cached SIS data. STU selects its verified owner.
            sisAccountHash = prefs[Keys.SIS_ACCOUNT_HASH] ?: prefs[Keys.ACCOUNT_HASH],
            stuAccountHash = prefs[Keys.STU_ACCOUNT_HASH]
        )
    }

    suspend fun current(): SessionSnapshot = snapshot.first()

    suspend fun setState(system: SchoolSystem, state: SessionState) {
        dataStore.edit { prefs ->
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

    suspend fun setAccountHash(system: SchoolSystem, hash: String?) {
        dataStore.edit { prefs ->
            val key = accountKey(system)
            if (hash.isNullOrBlank()) prefs.remove(key) else prefs[key] = hash
            if (hash != null) prefs[pendingAccountKey(system)] = false
            if (system == SchoolSystem.SIS) prefs.remove(Keys.ACCOUNT_HASH)
        }
    }

    /** One DataStore edit creates at most one partition, even across parallel repository calls. */
    suspend fun requireAccountHash(system: SchoolSystem): String {
        var hash: String? = null
        dataStore.edit { prefs ->
            val key = accountKey(system)
            hash = prefs[key] ?: if (system == SchoolSystem.SIS) prefs[Keys.ACCOUNT_HASH] else null
            if (hash == null) hash = AccountHasher.deviceLocalHash(null)
            prefs[key] = requireNotNull(hash)
            if (system == SchoolSystem.SIS) prefs.remove(Keys.ACCOUNT_HASH)
        }
        return requireNotNull(hash)
    }

    private fun accountKey(system: SchoolSystem) = when (system) {
        SchoolSystem.SIS -> Keys.SIS_ACCOUNT_HASH
        SchoolSystem.STU -> Keys.STU_ACCOUNT_HASH
    }

    private fun pendingAccountKey(system: SchoolSystem) = when (system) {
        SchoolSystem.SIS -> Keys.SIS_ACCOUNT_PENDING
        SchoolSystem.STU -> Keys.STU_ACCOUNT_PENDING
    }

    suspend fun markAccountPending(system: SchoolSystem) {
        dataStore.edit { it[pendingAccountKey(system)] = true }
    }

    suspend fun accountPending(system: SchoolSystem): Boolean = dataStore.data.first()[pendingAccountKey(system)] == true

    suspend fun setStudentIdHint(hint: String?) {
        dataStore.edit { prefs ->
            if (hint.isNullOrBlank()) prefs.remove(Keys.STUDENT_ID_HINT) else prefs[Keys.STUDENT_ID_HINT] = hint
        }
    }

    suspend fun studentIdHint(): String? = dataStore.data.first()[Keys.STUDENT_ID_HINT]

    /**
     * 学号提示的 Flow 形态。
     *
     * 一次性读（[studentIdHint]）只适合"写之前看一眼"的场景；凡是**显示**它的地方都必须用 Flow，
     * 否则写完不会回推，界面停在旧值（设置页"双真相源"那处就是这么来的）。
     */
    val studentIdHintFlow: Flow<String?> = dataStore.data.map { it[Keys.STUDENT_ID_HINT] }

    suspend fun setSemesterAnchor(monday: LocalDate?, confirmed: Boolean) {
        dataStore.edit { prefs ->
            if (monday == null) {
                prefs.remove(Keys.FIRST_WEEK_MONDAY)
                prefs[Keys.FIRST_WEEK_ANCHOR_CONFIRMED] = false
                prefs.remove(Keys.ANCHOR_YEAR)
                prefs.remove(Keys.ANCHOR_TERM)
            } else {
                prefs[Keys.FIRST_WEEK_MONDAY] = monday.toEpochDay()
                prefs[Keys.FIRST_WEEK_ANCHOR_CONFIRMED] = confirmed
                prefs[Keys.SEMESTER_YEAR]?.let { prefs[Keys.ANCHOR_YEAR] = it }
                prefs[Keys.SEMESTER_TERM]?.let { prefs[Keys.ANCHOR_TERM] = it }
            }
        }
    }

    /**
     * 学期锚点（第一教学周周一，以及它是否由用户确认过）的 Flow 形态。
     *
     * 这个值有**三个**写入方：设置页、后台刷新时的学期自动发现、以及 WebView 提取兜底。
     * 任何把它缓存进 ViewModel 再不管的做法，都会让课表页停在旧值上（Issue 1 就是这么来的）。
     */
    val semesterAnchorFlow: Flow<Pair<LocalDate?, Boolean>> = dataStore.data.map { prefs ->
        val configuration = prefs.semesterConfiguration()
        configuration.anchorFor(configuration.year, configuration.term)
    }

    suspend fun semesterAnchor(): Pair<LocalDate?, Boolean> = semesterAnchorFlow.first()

    /** 界面语言，热流形式，切换后界面立刻跟着变。 */
    val appLanguage: Flow<com.slai.campus.core.common.AppLanguage> =
        dataStore.data.map { prefs ->
            com.slai.campus.core.common.AppLanguage.fromStored(prefs[Keys.APP_LANGUAGE])
        }

    suspend fun setAppLanguage(language: com.slai.campus.core.common.AppLanguage) {
        dataStore.edit { it[Keys.APP_LANGUAGE] = language.storedValue }
    }

    /** 深浅色，同样做成热流，切换后界面立刻跟着变（不重建 Activity）。 */
    val appTheme: Flow<com.slai.campus.core.common.AppTheme> =
        dataStore.data.map { prefs ->
            com.slai.campus.core.common.AppTheme.fromStored(prefs[Keys.APP_THEME])
        }

    suspend fun setAppTheme(theme: com.slai.campus.core.common.AppTheme) {
        dataStore.edit { it[Keys.APP_THEME] = theme.storedValue }
    }

    suspend fun setSemesterCodes(year: String?, term: String?) {
        dataStore.edit { prefs ->
            if (prefs[Keys.SEMESTER_YEAR] != year || prefs[Keys.SEMESTER_TERM] != term) {
                prefs.remove(Keys.FIRST_WEEK_MONDAY)
                prefs.remove(Keys.FIRST_WEEK_ANCHOR_CONFIRMED)
                prefs.remove(Keys.ANCHOR_YEAR)
                prefs.remove(Keys.ANCHOR_TERM)
                prefs.remove(Keys.SEMESTER_VERIFIED_AT)
            }
            if (year.isNullOrBlank()) prefs.remove(Keys.SEMESTER_YEAR) else prefs[Keys.SEMESTER_YEAR] = year
            if (term.isNullOrBlank()) prefs.remove(Keys.SEMESTER_TERM) else prefs[Keys.SEMESTER_TERM] = term
        }
    }

    suspend fun semesterCodes(): Pair<String?, String?> {
        val prefs = dataStore.data.first()
        return prefs[Keys.SEMESTER_YEAR] to prefs[Keys.SEMESTER_TERM]
    }

    suspend fun semesterConfiguration(): SemesterConfiguration =
        dataStore.data.first().semesterConfiguration()

    /** Atomically publish one term with its anchor so observers never see mixed semesters. */
    suspend fun setSemesterConfiguration(
        year: String?,
        term: String?,
        monday: LocalDate?,
        confirmed: Boolean,
        verifiedAt: Long? = null
    ) {
        dataStore.edit { prefs ->
            if (prefs[Keys.SEMESTER_YEAR] != year || prefs[Keys.SEMESTER_TERM] != term) {
                prefs.remove(Keys.SEMESTER_VERIFIED_AT)
            }
            if (year.isNullOrBlank()) prefs.remove(Keys.SEMESTER_YEAR) else prefs[Keys.SEMESTER_YEAR] = year
            if (term.isNullOrBlank()) prefs.remove(Keys.SEMESTER_TERM) else prefs[Keys.SEMESTER_TERM] = term
            if (monday == null) {
                prefs.remove(Keys.FIRST_WEEK_MONDAY)
                prefs.remove(Keys.ANCHOR_YEAR)
                prefs.remove(Keys.ANCHOR_TERM)
            } else {
                prefs[Keys.FIRST_WEEK_MONDAY] = monday.toEpochDay()
                if (year.isNullOrBlank()) prefs.remove(Keys.ANCHOR_YEAR) else prefs[Keys.ANCHOR_YEAR] = year
                if (term.isNullOrBlank()) prefs.remove(Keys.ANCHOR_TERM) else prefs[Keys.ANCHOR_TERM] = term
            }
            prefs[Keys.FIRST_WEEK_ANCHOR_CONFIRMED] = monday != null && confirmed
            if (verifiedAt != null) prefs[Keys.SEMESTER_VERIFIED_AT] = verifiedAt
        }
    }

    private fun Preferences.semesterConfiguration() = SemesterConfiguration(
        year = this[Keys.SEMESTER_YEAR], term = this[Keys.SEMESTER_TERM],
        anchor = this[Keys.FIRST_WEEK_MONDAY]?.let(LocalDate::ofEpochDay),
        userConfirmed = this[Keys.FIRST_WEEK_ANCHOR_CONFIRMED] ?: false,
        anchorYear = this[Keys.ANCHOR_YEAR], anchorTerm = this[Keys.ANCHOR_TERM],
        verifiedAt = this[Keys.SEMESTER_VERIFIED_AT] ?: 0
    )

    suspend fun setBaseUrl(system: SchoolSystem, url: String) {
        dataStore.edit { prefs ->
            when (system) {
                SchoolSystem.SIS -> prefs[Keys.SIS_BASE_URL] = url
                SchoolSystem.STU -> prefs[Keys.STU_BASE_URL] = url
            }
        }
    }

    suspend fun baseUrl(system: SchoolSystem): String? {
        val prefs = dataStore.data.first()
        return when (system) {
            SchoolSystem.SIS -> prefs[Keys.SIS_BASE_URL]
            SchoolSystem.STU -> prefs[Keys.STU_BASE_URL]
        }
    }

    /** (sisBase, stuBase), either of which may be null when the user has not overridden it. */
    val baseUrls: Flow<Pair<String?, String?>> = dataStore.data.map { prefs ->
        prefs[Keys.SIS_BASE_URL] to prefs[Keys.STU_BASE_URL]
    }

    // ---- 应用内更新 ---------------------------------------------------------------------

    /** 自动检查更新。默认开 —— 关掉是用户的显式选择，所以没有值时不能猜成 false。 */
    val updateCheckEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.UPDATE_CHECK_ENABLED] ?: true
    }

    suspend fun updateCheckEnabledNow(): Boolean = updateCheckEnabled.first()

    suspend fun setUpdateCheckEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.UPDATE_CHECK_ENABLED] = enabled }
    }

    /** 被「忽略此版本」跳过的版本号。 */
    suspend fun updateIgnoredVersion(): String? =
        dataStore.data.first()[Keys.UPDATE_IGNORED_VERSION]

    suspend fun setUpdateIgnoredVersion(version: String?) {
        dataStore.edit { prefs ->
            if (version.isNullOrBlank()) prefs.remove(Keys.UPDATE_IGNORED_VERSION)
            else prefs[Keys.UPDATE_IGNORED_VERSION] = version
        }
    }

    suspend fun updateLastCheckAt(): Long? = dataStore.data.first()[Keys.UPDATE_LAST_CHECK_AT]

    suspend fun setUpdateLastCheckAt(at: Long) {
        dataStore.edit { it[Keys.UPDATE_LAST_CHECK_AT] = at }
    }

    suspend fun updateEtag(): String? = dataStore.data.first()[Keys.UPDATE_ETAG]

    suspend fun setUpdateEtag(etag: String) {
        dataStore.edit { it[Keys.UPDATE_ETAG] = etag }
    }

    suspend fun updateCache(): Triple<String?, String?, String?> {
        val prefs = dataStore.data.first()
        return Triple(prefs[Keys.UPDATE_CACHED_SOURCE], prefs[Keys.UPDATE_ETAG], prefs[Keys.UPDATE_CACHED_PAYLOAD])
    }

    suspend fun setUpdateCache(source: String, etag: String?, payload: String) {
        dataStore.edit {
            it[Keys.UPDATE_CACHED_SOURCE] = source
            it[Keys.UPDATE_CACHED_PAYLOAD] = payload
            if (etag == null) it.remove(Keys.UPDATE_ETAG) else it[Keys.UPDATE_ETAG] = etag
        }
    }

    suspend fun updateLastAttemptAt(): Long? = dataStore.data.first()[Keys.UPDATE_LAST_ATTEMPT_AT]
    suspend fun setUpdateLastAttemptAt(at: Long) {
        dataStore.edit { it[Keys.UPDATE_LAST_ATTEMPT_AT] = at }
    }

    /**
     * 首页要不要显示课表。默认 true —— 关闭是「我确实不需要课表」的显式选择，
     * 所以没有值时不能猜成 false。
     */
    val showTimetableOnHome: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SHOW_TIMETABLE_ON_HOME] ?: true
    }

    suspend fun setShowTimetableOnHome(show: Boolean) {
        dataStore.edit { it[Keys.SHOW_TIMETABLE_ON_HOME] = show }
    }

    val reminderConfig: Flow<ReminderConfig> = dataStore.data.map { prefs ->
        ReminderConfig(
            enabled = prefs[Keys.REMINDERS_ENABLED] ?: true,
            leadMinutes = prefs[Keys.REMINDER_LEAD_MINUTES] ?: 15,
            exact = prefs[Keys.REMINDER_EXACT] ?: false,
            windowDays = prefs[Keys.REMINDER_WINDOW_DAYS] ?: 14
        )
    }

    suspend fun reminderConfigOnce(): ReminderConfig = reminderConfig.first()

    suspend fun setRemindersEnabled(enabled: Boolean) =
        dataStore.edit { it[Keys.REMINDERS_ENABLED] = enabled }

    suspend fun setReminderLeadMinutes(minutes: Int) =
        dataStore.edit { it[Keys.REMINDER_LEAD_MINUTES] = minutes.coerceIn(1, 120) }

    suspend fun setReminderExact(exact: Boolean) =
        dataStore.edit { it[Keys.REMINDER_EXACT] = exact }

    suspend fun setIntegrationMode(system: SchoolSystem, mode: com.slai.campus.core.common.IntegrationMode) {
        dataStore.edit { prefs ->
            when (system) {
                SchoolSystem.SIS -> prefs[Keys.SIS_MODE] = mode.name
                SchoolSystem.STU -> prefs[Keys.STU_MODE] = mode.name
            }
        }
    }

    suspend fun integrationMode(system: SchoolSystem): com.slai.campus.core.common.IntegrationMode? {
        val prefs = dataStore.data.first()
        val raw = when (system) {
            SchoolSystem.SIS -> prefs[Keys.SIS_MODE]
            SchoolSystem.STU -> prefs[Keys.STU_MODE]
        }
        return raw?.let { runCatching { com.slai.campus.core.common.IntegrationMode.valueOf(it) }.getOrNull() }
    }

    /** Daily attendance target in minutes. The college requires 6 hours on working days. */
    val attendanceGoalMinutes: Flow<Int> = dataStore.data.map { it[Keys.ATTENDANCE_GOAL_MINUTES] ?: 360 }

    suspend fun attendanceGoalMinutesOnce(): Int = attendanceGoalMinutes.first()

    suspend fun setAttendanceGoalMinutes(minutes: Int) =
        dataStore.edit { it[Keys.ATTENDANCE_GOAL_MINUTES] = minutes.coerceIn(30, 24 * 60) }

    suspend fun scheduledReminderIds(): Set<String> =
        dataStore.data.first()[Keys.SCHEDULED_REMINDERS] ?: emptySet()

    suspend fun setScheduledReminderIds(ids: Set<String>) {
        dataStore.edit { prefs -> prefs[Keys.SCHEDULED_REMINDERS] = ids }
    }
}

data class ReminderConfig(
    val enabled: Boolean = true,
    val leadMinutes: Int = 15,
    val exact: Boolean = false,
    val windowDays: Int = 14
)
