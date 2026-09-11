package com.slai.campus.data.attendance

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.IoDispatcher
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.core.common.describe
import com.slai.campus.core.database.AttendanceDao
import com.slai.campus.core.database.AttendanceMetaDao
import com.slai.campus.core.database.AttendanceMetaEntity
import com.slai.campus.core.database.AttendancePunchDao
import com.slai.campus.core.database.AttendancePunchEntity
import com.slai.campus.core.database.AttendanceRecordEntity
import com.slai.campus.core.network.NetworkMonitor
import com.slai.campus.core.session.SessionManager
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.session.SessionStore
import com.slai.campus.data.stu.StuAttendanceDataSource
import com.slai.campus.data.stu.StuConfig
import com.slai.campus.data.stu.StuPunchDataSource
import com.slai.campus.domain.attendance.AttendanceMonth
import com.slai.campus.domain.attendance.AttendancePunch
import com.slai.campus.domain.attendance.PunchDirection
import com.slai.campus.domain.attendance.AttendanceRecord
import com.slai.campus.domain.attendance.AttendanceRefreshResult
import com.slai.campus.domain.attendance.networkFailureResult
import com.slai.campus.domain.attendance.AttendanceRepository
import com.slai.campus.domain.attendance.AttendanceSyncState
import com.slai.campus.data.stu.StuAttendanceParser
import com.slai.campus.domain.attendance.AttendanceWeek
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attendance ledger, cached per month.
 *
 * Same discipline as the timetable: reads always come from Room, writes only happen after a fully
 * validated payload, and a failure never clears a cached month. Unlike the old check-in guess, the
 * school actually reports a record per day, so there is no "unknown" state to invent — a missing day
 * simply has no row yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AttendanceRepositoryImpl @Inject constructor(
    private val attendanceDao: AttendanceDao,
    private val attendanceMetaDao: AttendanceMetaDao,
    private val punchDao: AttendancePunchDao,
    private val punchDataSource: StuPunchDataSource,
    private val sessionManager: SessionManager,
    private val sessionStore: SessionStore,
    private val dataSource: StuAttendanceDataSource,
    private val networkMonitor: NetworkMonitor,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val io: CoroutineDispatcher
) : AttendanceRepository {

    private val refreshMutex = Mutex()
    private val punchMutex = Mutex()
    private val punchSyncState = kotlinx.coroutines.flow.MutableStateFlow(AttendanceSyncState())

    override fun observeDay(date: LocalDate): Flow<AttendanceRecord?> =
        sessionManager.state
            .map { it.accountHash }
            .distinctUntilChanged()
            .flatMapLatest { hash ->
                if (hash.isNullOrBlank()) {
                    flowOf(null)
                } else {
                    attendanceDao.observeDay(hash, date).map { it?.toDomain() }
                }
            }

    override fun observeMonth(month: String): Flow<AttendanceMonth> =
        sessionManager.state
            .map { it.accountHash }
            .distinctUntilChanged()
            .flatMapLatest { hash ->
                if (hash.isNullOrBlank()) {
                    flowOf(AttendanceMonth(month))
                } else {
                    kotlinx.coroutines.flow.combine(
                        attendanceDao.observeMonth(hash, month),
                        attendanceMetaDao.observe(hash, month)
                    ) { rows, meta -> rows.toMonth(month, meta?.stats) }
                }
            }

    override fun observeSyncState(): Flow<AttendanceSyncState> =
        sessionManager.state
            .map { it.accountHash }
            .distinctUntilChanged()
            .flatMapLatest { hash ->
                if (hash.isNullOrBlank()) {
                    flowOf(AttendanceSyncState())
                } else {
                    attendanceMetaDao.observe(hash, dataSource.currentMonth()).map { meta ->
                        AttendanceSyncState(
                            lastSuccessAt = meta?.lastSuccessAt?.let(java.time.Instant::ofEpochMilli),
                            lastAttemptAt = meta?.lastAttemptAt?.let(java.time.Instant::ofEpochMilli),
                            lastError = meta?.lastError
                        )
                    }
                }
            }

    override fun observePunches(date: LocalDate): Flow<List<AttendancePunch>> =
        sessionManager.state
            .map { it.accountHash }
            .distinctUntilChanged()
            .flatMapLatest { hash ->
                if (hash.isNullOrBlank()) {
                    flowOf(emptyList())
                } else {
                    punchDao.observeDay(hash, date).map { rows -> rows.map { it.toDomain() } }
                }
            }

    override fun observePunches(from: LocalDate, to: LocalDate): Flow<List<AttendancePunch>> =
        sessionManager.state
            .map { it.accountHash }
            .distinctUntilChanged()
            .flatMapLatest { hash ->
                if (hash.isNullOrBlank()) {
                    flowOf(emptyList())
                } else {
                    punchDao.observeRange(hash, from, to).map { rows -> rows.map { it.toDomain() } }
                }
            }

    override fun observePunchSyncState(): Flow<AttendanceSyncState> = punchSyncState.asStateFlow()

    override suspend fun refreshPunches(from: LocalDate, to: LocalDate): AttendanceRefreshResult =
        withContext(io) {
            punchMutex.withLock {
                val accountHash = sessionManager.requireAccountHash()
                val attemptedAt = System.currentTimeMillis()

                if (!networkMonitor.hasNetwork) {
                    punchSyncState.value = punchSyncState.value.copy(lastAttemptAt = java.time.Instant.ofEpochMilli(attemptedAt), lastError = "offline")
                    return@withLock AttendanceRefreshResult.Offline(cached = punchDao.countRange(accountHash, from, to) > 0)
                }

                val baseUrl = StuConfig.baseUrlOrDefault(sessionStore.baseUrl(SchoolSystem.STU))
                val outcome = punchDataSource.fetchPunches(baseUrl, from, to)
                AppLog.d("punch fetch $from..$to: ${outcome.result.describe()}")

                when (val result = outcome.result) {
                    is RemoteResult.Success -> {
                        sessionManager.set(SchoolSystem.STU, SessionState.AUTHENTICATED)
                        val rows = result.data.map { it.toEntity(accountHash, attemptedAt) }
                        punchDao.replaceRange(accountHash, from, to, rows)
                        punchSyncState.value = AttendanceSyncState(
                            lastSuccessAt = java.time.Instant.now(),
                            lastAttemptAt = java.time.Instant.ofEpochMilli(attemptedAt)
                        )
                        AppLog.i("punches synced $from..$to: ${rows.size} record(s)")
                        AttendanceRefreshResult.Success(to.toString(), rows.size)
                    }
                    RemoteResult.SessionExpired -> {
                        sessionManager.set(SchoolSystem.STU, SessionState.EXPIRED)
                        punchSyncState.value = punchSyncState.value.copy(
                            lastAttemptAt = java.time.Instant.ofEpochMilli(attemptedAt), lastError = "session expired"
                        )
                        AttendanceRefreshResult.SessionExpired
                    }
                    is RemoteResult.NetworkUnavailable -> {
                        punchSyncState.value = punchSyncState.value.copy(
                            lastAttemptAt = java.time.Instant.ofEpochMilli(attemptedAt), lastError = result.reason
                        )
                        AttendanceRefreshResult.Offline(cached = punchDao.countRange(accountHash, from, to) > 0)
                    }
                    is RemoteResult.SchemaChanged -> {
                        punchSyncState.value = punchSyncState.value.copy(
                            lastAttemptAt = java.time.Instant.ofEpochMilli(attemptedAt), lastError = result.reason
                        )
                        AttendanceRefreshResult.SchemaChanged(result.reason)
                    }
                    is RemoteResult.ServerError -> {
                        punchSyncState.value = punchSyncState.value.copy(
                            lastAttemptAt = java.time.Instant.ofEpochMilli(attemptedAt), lastError = "HTTP ${result.code}"
                        )
                        AttendanceRefreshResult.ServerError(result.code)
                    }
                    is RemoteResult.UnknownError -> {
                        punchSyncState.value = punchSyncState.value.copy(
                            lastAttemptAt = java.time.Instant.ofEpochMilli(attemptedAt), lastError = result.reason
                        )
                        AttendanceRefreshResult.Failed(result.reason)
                    }
                }
            }
        }

    private fun AttendancePunch.toEntity(accountHash: String, updatedAt: Long) = AttendancePunchEntity(
        id = id,
        accountHash = accountHash,
        time = time,
        date = date,
        direction = direction.name,
        channel = channel,
        place = place,
        openingType = openingType,
        result = result,
        updatedAt = updatedAt
    )

    private fun AttendancePunchEntity.toDomain() = AttendancePunch(
        id = id,
        time = time,
        direction = runCatching { PunchDirection.valueOf(direction) }.getOrDefault(PunchDirection.UNKNOWN),
        channel = channel,
        place = place,
        openingType = openingType,
        result = result
    )

    override suspend fun cachedMonths(): List<String> = withContext(io) {
        val hash = sessionManager.current().accountHash ?: return@withContext emptyList()
        attendanceMetaDao.all(hash).map { it.month }
    }

    override suspend fun refresh(month: String): AttendanceRefreshResult = withContext(io) {
        refreshMutex.withLock {
            if (!StuAttendanceDataSource.isValidMonth(month)) {
                return@withLock AttendanceRefreshResult.Failed("月份格式应为 YYYY-MM")
            }
            val accountHash = sessionManager.requireAccountHash()
            val attemptedAt = System.currentTimeMillis()

            if (!networkMonitor.hasNetwork) {
                recordAttempt(accountHash, month, attemptedAt, "offline")
                return@withLock AttendanceRefreshResult.Offline(cached = hasCache(accountHash, month))
            }

            val baseUrl = StuConfig.baseUrlOrDefault(sessionStore.baseUrl(SchoolSystem.STU))
            val outcome = dataSource.fetchMonth(baseUrl, month)
            AppLog.d("attendance fetch $month: ${outcome.result.describe()}")

            when (val result = outcome.result) {
                is RemoteResult.Success -> {
                    sessionManager.set(SchoolSystem.STU, SessionState.AUTHENTICATED)
                    val rows = result.data.records.map { it.toEntity(accountHash, month, attemptedAt) }
                    attendanceDao.replaceMonth(accountHash, month, rows)
                    attendanceMetaDao.upsert(
                        AttendanceMetaEntity(
                            accountHash = accountHash,
                            month = month,
                            lastSuccessAt = System.currentTimeMillis(),
                            lastAttemptAt = attemptedAt,
                            lastError = null,
                            stats = result.data.stats.entries.joinToString(";") { "${it.key}=${it.value}" }
                        )
                    )
                    AppLog.i("attendance synced $month: ${rows.size} day(s)")
                    AttendanceRefreshResult.Success(month, rows.size)
                }

                RemoteResult.SessionExpired -> {
                    sessionManager.set(SchoolSystem.STU, SessionState.EXPIRED)
                    recordAttempt(accountHash, month, attemptedAt, "session expired")
                    AttendanceRefreshResult.SessionExpired
                }

                is RemoteResult.NetworkUnavailable -> {
                    recordAttempt(accountHash, month, attemptedAt, result.reason)
                    // 设备有网 = 是"连不上学校"（很可能是离开了校园网），不是"离线"。
                    networkFailureResult(
                        hasNetwork = networkMonitor.hasNetwork,
                        hasCache = hasCache(accountHash, month),
                        reason = result.reason
                    )
                }

                is RemoteResult.SchemaChanged -> {
                    recordAttempt(accountHash, month, attemptedAt, result.reason)
                    AttendanceRefreshResult.SchemaChanged(result.reason)
                }

                is RemoteResult.ServerError -> {
                    recordAttempt(accountHash, month, attemptedAt, "HTTP ${result.code}")
                    AttendanceRefreshResult.ServerError(result.code)
                }

                is RemoteResult.UnknownError -> {
                    recordAttempt(accountHash, month, attemptedAt, result.reason)
                    AttendanceRefreshResult.Failed(result.reason)
                }
            }
        }
    }

    private suspend fun recordAttempt(accountHash: String, month: String, at: Long, error: String) {
        val existing = attendanceMetaDao.all(accountHash).firstOrNull { it.month == month }
        attendanceMetaDao.upsert(
            (existing ?: AttendanceMetaEntity(accountHash, month, null, null, null, null))
                .copy(lastAttemptAt = at, lastError = error)
        )
    }

    private suspend fun hasCache(accountHash: String, month: String): Boolean =
        attendanceDao.countMonth(accountHash, month) > 0

    private fun AttendanceRecord.toEntity(accountHash: String, month: String, updatedAt: Long) =
        AttendanceRecordEntity(
            accountHash = accountHash,
            date = date,
            month = month,
            weekDay = weekDay,
            dayType = dayType,
            isHoliday = isHoliday,
            firstSwipe = firstSwipe,
            lastSwipe = lastSwipe,
            enterCount = enterCount,
            exitCount = exitCount,
            durationText = durationText,
            durationMinutes = durationMinutes,
            qualified = qualified,
            leave = leave,
            appeal = appeal,
            swipes = swipes.joinToString(",") { it.toString() }.takeIf { it.isNotEmpty() },
            updatedAt = updatedAt
        )

    private fun AttendanceRecordEntity.toDomain() = AttendanceRecord(
        date = date,
        weekDay = weekDay,
        dayType = dayType,
        isHoliday = isHoliday,
        firstSwipe = firstSwipe,
        lastSwipe = lastSwipe,
        enterCount = enterCount,
        exitCount = exitCount,
        durationText = durationText,
        durationMinutes = durationMinutes,
        qualified = qualified,
        leave = leave,
        appeal = appeal,
        swipes = swipes.orEmpty().split(',').mapNotNull { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
    )

    /** Groups cached rows into weeks the same way the server does (Monday-based). */
    private fun List<AttendanceRecordEntity>.toMonth(
        month: String,
        statsText: String?
    ): AttendanceMonth {
        val stats = statsText.orEmpty()
            .split(';')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val summary = StuAttendanceParser.toSummary(stats)
        if (isEmpty()) return AttendanceMonth(month = month, stats = stats, summary = summary)
        val byWeek = groupBy { row ->
            row.date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        }
        val weeks = byWeek.entries.sortedBy { it.key }.map { (monday, rows) ->
            AttendanceWeek(
                label = "${monday.monthValue}月${monday.dayOfMonth}日起",
                range = "$monday,${monday.plusDays(6)}",
                records = rows.sortedBy { it.date }.map { it.toDomain() }
            )
        }
        return AttendanceMonth(month = month, weeks = weeks, stats = stats, summary = summary)
    }
}
