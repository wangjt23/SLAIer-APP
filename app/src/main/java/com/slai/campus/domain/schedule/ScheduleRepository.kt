package com.slai.campus.domain.schedule

import com.slai.campus.core.common.Failure
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * The single entry point the UI uses for timetable data.
 *
 * Contract (from the design plan):
 *  - `observe*` **never** touches the network; the home screen must render offline.
 *  - `refresh()` only replaces cached rows after a fully parsed, fully validated payload arrives.
 *  - A failure never clears the cache and never renders as "no classes".
 */
interface ScheduleRepository {

    /** Today's classes, ordered by start time. Reads Room only. */
    fun observeToday(): Flow<List<ClassOccurrence>>

    /** The whole current teaching week, ordered by date then start time. Reads Room only. */
    fun observeWeek(): Flow<List<ClassOccurrence>>

    /** Classes for an explicit date range (used by the week pager). Reads Room only. */
    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<ClassOccurrence>>

    /** Sync metadata for the status row: last success, last attempt, last failure. */
    fun observeSyncState(): Flow<SyncState>

    /** Result of the most recent [refresh], as a hot flow for the UI. */
    fun observeLastRefresh(): Flow<RefreshResult?>

    /** True while a refresh is in flight, including one started by the background worker. */
    fun observeIsRefreshing(): Flow<Boolean>

    /**
     * What the in-flight refresh is currently doing. A spinner with no explanation is what made the
     * first version look "stuck"; every network phase is now reportable.
     */
    fun observePhase(): Flow<RefreshPhase>

    /**
     * Step-by-step record of the most recent refresh: which branch was taken, what each endpoint
     * answered, how many rows were written.
     *
     * Added because the diagnostics screen used to probe the *data sources* directly, which is a
     * different code path from [refresh]. It could report a perfect success while the real refresh
     * was failing a step earlier — repeatedly sending the investigation in the wrong direction.
     */
    fun observeRefreshTrace(): Flow<List<String>>

    /** Attempts a full remote fetch. Safe to call from a Worker. */
    suspend fun refresh(reason: RefreshReason = RefreshReason.MANUAL): RefreshResult

    /** Loads cached rows once (for notification scheduling from a Worker). */
    suspend fun snapshotUpcoming(from: LocalDate, days: Int): List<ClassOccurrence>
}

enum class RefreshReason { MANUAL, APP_START, BACKGROUND, AFTER_LOGIN }

/** Coarse progress of a refresh, shown next to the spinner. */
enum class RefreshPhase(val label: String) {
    IDLE(""),
    READING_PROVIDER("正在读取接口配置…"),
    DISCOVERING("正在读取学年学期…"),
    NATIVE("正在请求教务接口…"),
    WEBVIEW("正在从网页提取…"),
    SAVING("正在写入本地缓存…"),
    DONE("")
}

/** Everything the home screen needs to explain the freshness of the data it is showing. */
data class SyncState(
    val system: com.slai.campus.core.common.SchoolSystem,
    val lastSuccessAt: java.time.Instant? = null,
    val lastAttemptAt: java.time.Instant? = null,
    val lastFailure: Failure? = null,
    val parserVersion: String? = null,
    val integrationMode: com.slai.campus.core.common.IntegrationMode? = null
) {
    val hasEverSucceeded: Boolean get() = lastSuccessAt != null
}

/** Outcome of a refresh, mapped to the exact message the plan requires in the UI. */
sealed interface RefreshResult {
    data class Success(val added: Int, val total: Int) : RefreshResult
    data object SessionExpired : RefreshResult
    data class Offline(val cached: Boolean) : RefreshResult
    data class SchemaChanged(val reason: String) : RefreshResult
    data class ServerError(val code: Int) : RefreshResult
    data class Failed(val reason: String) : RefreshResult

    val isSuccess: Boolean get() = this is Success
}
