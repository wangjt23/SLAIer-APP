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
import com.slai.campus.data.stu.StuAttendancePeriodParser
import com.slai.campus.data.stu.StuConfig
import com.slai.campus.data.stu.StuPunchDataSource
import com.slai.campus.domain.attendance.AttendanceMonth
import com.slai.campus.domain.attendance.AttendancePeriod
import com.slai.campus.domain.attendance.AttendancePeriodResult
import com.slai.campus.domain.attendance.currentAttendancePeriod
import com.slai.campus.domain.attendance.AttendancePunch
import com.slai.campus.domain.attendance.PunchDirection
import com.slai.campus.domain.attendance.AttendanceRecord
import com.slai.campus.domain.attendance.AttendanceRefreshResult
import com.slai.campus.domain.attendance.AttendanceRefreshScope
import com.slai.campus.domain.attendance.AttendanceRefreshOrigin
import com.slai.campus.domain.attendance.AttendanceRefreshUpdates
import com.slai.campus.domain.attendance.combineAttendanceRefreshResults
import com.slai.campus.domain.attendance.networkFailureResult
import com.slai.campus.domain.attendance.AttendanceRepository
import com.slai.campus.data.stu.StuAttendanceParser
import com.slai.campus.domain.attendance.AttendanceWeek
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
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
    private val stuRemote: com.slai.campus.data.stu.StuRemoteDataSource,
    private val networkMonitor: NetworkMonitor,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val io: CoroutineDispatcher
) : AttendanceRepository {

    private val refreshMutex = Mutex()
    private val punchMutex = Mutex()
    private val completeRefreshMutex = Mutex()
    private val refreshResults = AttendanceRefreshUpdates()
    override val refreshUpdates = refreshResults.state

    override fun observePeriods(): Flow<List<AttendancePeriod>> = sessionManager.state
        .map { it.accountHashOf(SchoolSystem.STU) }
        .distinctUntilChanged()
        .flatMapLatest { owner ->
            if (owner == null) flowOf(emptyList()) else attendanceMetaDao.observeAll(owner).map { metas ->
                metas.mapNotNull { StuAttendancePeriodParser.decode(it.periodWeeks, it.month)?.period }
            }
        }

    override suspend fun periodForMonth(month: String): AttendancePeriodResult = withContext(io) {
        refreshMutex.withLock {
            if (!StuAttendanceDataSource.isValidMonth(month)) return@withLock unavailablePeriod("月份格式应为 YYYY-MM")
            val owner = sessionManager.requireAccountHash(SchoolSystem.STU)
            loadPeriodLocked(owner, month)
        }
    }

    override suspend fun resolvePeriod(date: LocalDate): AttendancePeriodResult = withContext(io) {
        refreshMutex.withLock {
            val owner = sessionManager.requireAccountHash(SchoolSystem.STU)
            val version = sessionManager.accountVersion(SchoolSystem.STU)
            val cached = attendanceMetaDao.all(owner)
            if (!periodOwnerIsCurrent(owner, version)) {
                return@withLock AttendancePeriodResult.Unavailable(AttendanceRefreshResult.Superseded)
            }
            val known = cached.mapNotNull { StuAttendancePeriodParser.decode(it.periodWeeks, it.month)?.period }
            val matching = known.filter { date in it }
            if (matching.size > 1 && !networkMonitor.hasNetwork) {
                return@withLock unavailablePeriod("学校考勤周期相互重叠，暂时无法确定当前月份")
            }
            // A school correction can make two formerly valid cached periods overlap. Reload
            // both before deciding, instead of leaving an online client permanently stuck.
            val pendingConflictChecks = if (matching.size > 1) matching.map { it.month }.toMutableSet() else mutableSetOf()
            val cachedPeriod = currentAttendancePeriod(date, known)
            val freshPeriod = cachedPeriod?.takeIf { period ->
                cached.firstOrNull { it.month == period.month }?.periodUpdatedAt?.let(::periodCacheIsFresh) == true
            }
            if (freshPeriod != null || (!networkMonitor.hasNetwork && cachedPeriod != null)) {
                return@withLock AttendancePeriodResult.Found(freshPeriod ?: requireNotNull(cachedPeriod))
            }

            // Most days need only the natural month's small week list. At either boundary the
            // school-provided start/end tells us which adjacent month to inspect next.
            val natural = YearMonth.from(date)
            val candidates = ArrayDeque((listOf(natural) + pendingConflictChecks.map(YearMonth::parse) +
                listOf(natural.plusMonths(1), natural.minusMonths(1))).distinct())
            val inspected = mutableSetOf<String>()
            var lastFailure: AttendancePeriodResult? = null
            while (candidates.isNotEmpty() && inspected.size < 3) {
                val candidate = candidates.removeFirst().toString()
                if (!inspected.add(candidate)) continue
                if (sessionManager.accountVersion(SchoolSystem.STU) != version) {
                    return@withLock AttendancePeriodResult.Unavailable(AttendanceRefreshResult.Superseded)
                }
                val forceRefresh = candidate in pendingConflictChecks
                when (val loaded = loadPeriodLocked(owner, candidate, forceRefresh)) {
                    is AttendancePeriodResult.Found -> {
                        pendingConflictChecks.remove(candidate)
                        val combined = attendanceMetaDao.all(owner).mapNotNull {
                            StuAttendancePeriodParser.decode(it.periodWeeks, it.month)?.period
                        }
                        if (!periodOwnerIsCurrent(owner, version)) {
                            return@withLock AttendancePeriodResult.Unavailable(AttendanceRefreshResult.Superseded)
                        }
                        val overlapping = combined.filter { date in it }
                        if (overlapping.size > 1) {
                            overlapping.filter { it.month !in inspected }.forEach {
                                pendingConflictChecks += it.month
                                candidates.addFirst(YearMonth.parse(it.month))
                            }
                            if (pendingConflictChecks.isEmpty()) return@withLock unavailablePeriod("学校考勤周期相互重叠，暂时无法确定当前月份")
                        }
                        if (pendingConflictChecks.isEmpty()) currentAttendancePeriod(date, combined)?.let {
                            return@withLock AttendancePeriodResult.Found(it)
                        }
                        val next = if (date < loaded.period.start) YearMonth.parse(candidate).minusMonths(1)
                            else YearMonth.parse(candidate).plusMonths(1)
                        if (next in natural.minusMonths(1)..natural.plusMonths(1)) candidates.addFirst(next)
                    }
                    is AttendancePeriodResult.Unavailable -> {
                        pendingConflictChecks.remove(candidate)
                        if (loaded.reason is AttendanceRefreshResult.SessionExpired ||
                            loaded.reason is AttendanceRefreshResult.Superseded) return@withLock loaded
                        lastFailure = loaded
                    }
                }
            }
            val latest = attendanceMetaDao.all(owner).mapNotNull {
                StuAttendancePeriodParser.decode(it.periodWeeks, it.month)?.period
            }
            if (!periodOwnerIsCurrent(owner, version)) {
                AttendancePeriodResult.Unavailable(AttendanceRefreshResult.Superseded)
            } else currentAttendancePeriod(date, latest)?.let { AttendancePeriodResult.Found(it) }
                ?: lastFailure ?: unavailablePeriod("学校尚未提供当前考勤日所属的完整周期")
        }
    }

    /** Caller holds refreshMutex, shared with summary metadata writes. Never selects an owner. */
    private suspend fun loadPeriodLocked(owner: String, month: String, forceRefresh: Boolean = false): AttendancePeriodResult {
        val accountVersion = sessionManager.accountVersion(SchoolSystem.STU)
        val authenticationVersion = sessionManager.authenticationVersion(SchoolSystem.STU)
        if (!periodOwnerIsCurrent(owner, accountVersion)) {
            return AttendancePeriodResult.Unavailable(AttendanceRefreshResult.Superseded)
        }
        val existing = attendanceMetaDao.all(owner).firstOrNull { it.month == month }
        if (!periodOwnerIsCurrent(owner, accountVersion)) {
            return AttendancePeriodResult.Unavailable(AttendanceRefreshResult.Superseded)
        }
        val metadata = StuAttendancePeriodParser.decode(existing?.periodWeeks, month)
        val cached = metadata?.period
        if (!forceRefresh && existing?.periodUpdatedAt?.let(::periodCacheIsFresh) == true && metadata != null) {
            return cached?.let { AttendancePeriodResult.Found(it) }
                ?: unavailablePeriod("学校尚未提供 $month 的考勤周期")
        }
        if (!networkMonitor.hasNetwork) return cached?.let { AttendancePeriodResult.Found(it) }
            ?: AttendancePeriodResult.Unavailable(AttendanceRefreshResult.Offline(false))

        val base = StuConfig.baseUrlOrDefault(sessionStore.baseUrl(SchoolSystem.STU))
        var result = dataSource.fetchPeriod(base, month)
        if (result is RemoteResult.SessionExpired &&
            sessionManager.responseIsCurrent(SchoolSystem.STU, accountVersion, authenticationVersion) &&
            stuRemote.renewSession(base, canContinue = {
                sessionManager.responseIsCurrent(SchoolSystem.STU, accountVersion, authenticationVersion)
            })) result = dataSource.fetchPeriod(base, month)
        if (!sessionManager.responseIsCurrent(SchoolSystem.STU, accountVersion, authenticationVersion)) {
            return AttendancePeriodResult.Unavailable(AttendanceRefreshResult.Superseded)
        }
        return when (val response = result) {
            is RemoteResult.Success -> {
                // An empty future month can be cached briefly, but cannot erase a known cycle.
                if (response.data.period == null && cached != null) return AttendancePeriodResult.Found(cached)
                val accepted = sessionManager.withCurrentAccount(SchoolSystem.STU, owner, accountVersion, authenticationVersion) { _, _ ->
                    attendanceMetaDao.upsert((existing ?: AttendanceMetaEntity(owner, month, null, null, null, null))
                        .copy(periodWeeks = StuAttendancePeriodParser.encode(response.data), periodUpdatedAt = timeProvider.epochMillis()))
                    true
                } == true
                if (!accepted) {
                    AttendancePeriodResult.Unavailable(AttendanceRefreshResult.Superseded)
                } else response.data.period?.let { AttendancePeriodResult.Found(it) }
                    ?: unavailablePeriod("学校尚未提供 $month 的考勤周期")
            }
            RemoteResult.SessionExpired -> {
                if (sessionManager.set(SchoolSystem.STU, SessionState.EXPIRED, accountVersion, authenticationVersion))
                    AttendancePeriodResult.Unavailable(AttendanceRefreshResult.SessionExpired)
                else AttendancePeriodResult.Unavailable(AttendanceRefreshResult.Superseded)
            }
            else -> cached?.let { AttendancePeriodResult.Found(it) } ?: AttendancePeriodResult.Unavailable(when (response) {
                is RemoteResult.NetworkUnavailable -> networkFailureResult(networkMonitor.hasNetwork, false, response.reason)
                is RemoteResult.ServerError -> AttendanceRefreshResult.ServerError(response.code)
                is RemoteResult.SchemaChanged -> AttendanceRefreshResult.SchemaChanged(response.reason)
                is RemoteResult.UnknownError -> AttendanceRefreshResult.Failed(response.reason)
                else -> error("Handled above")
            })
        }
    }

    private fun periodCacheIsFresh(updatedAt: Long): Boolean = timeProvider.epochMillis() - updatedAt in 0 until 6 * 60 * 60 * 1_000L

    private suspend fun periodOwnerIsCurrent(owner: String, version: Long): Boolean =
        sessionManager.withCurrentAccount(SchoolSystem.STU, owner, version, null) { _, currentVersion -> currentVersion == version } == true

    private fun unavailablePeriod(reason: String) = AttendancePeriodResult.Unavailable(AttendanceRefreshResult.SchemaChanged(reason))

    private class RefreshEvidence {
        var authenticationVersion: Long? = null
    }

    override suspend fun refreshWithPunches(month: String, from: LocalDate, to: LocalDate,
                                          origin: AttendanceRefreshOrigin): AttendanceRefreshResult =
        completeRefreshMutex.withLock {
            val startedVersion = sessionManager.accountVersion(SchoolSystem.STU)
            val evidence = RefreshEvidence()
            val summary = refreshMonth(month, evidence)
            if (summary is AttendanceRefreshResult.Superseded) return@withLock summary
            if (evidence.authenticationVersion == null) return@withLock summary
            val version = sessionManager.accountVersion(SchoolSystem.STU)
            val owner = sessionManager.current().accountHashOf(SchoolSystem.STU)
            if (sessionManager.accountVersion(SchoolSystem.STU) != version || (!summary.isSuccess &&
                    evidence.authenticationVersion != sessionManager.authenticationVersion(SchoolSystem.STU))) {
                return@withLock AttendanceRefreshResult.Superseded
            }
            // A full summary can reveal a corrected/new school week list after the lightweight
            // lookup. Include that complete range before calling this refresh successful.
            val actualPeriod = owner?.let { hash -> attendanceMetaDao.all(hash).firstOrNull { it.month == month } }
                ?.let { StuAttendancePeriodParser.decode(it.periodWeeks, month)?.period }
            val actualFrom = actualPeriod?.start?.minusDays(1)?.let { minOf(from, it) } ?: from
            val actualTo = actualPeriod?.endInclusive?.plusDays(1)?.let { maxOf(to, it) } ?: to
            val result = if (summary is AttendanceRefreshResult.SessionExpired) summary else {
                val punches = refreshPunchRange(actualFrom, actualTo, evidence)
                if (sessionManager.accountVersion(SchoolSystem.STU) != version) AttendanceRefreshResult.Superseded
                else combineAttendanceRefreshResults(summary, punches)
            }
            if (owner == null) return@withLock result
            sessionManager.withCurrentAccount(SchoolSystem.STU, owner, startedVersion,
                if (result.isSuccess) null else evidence.authenticationVersion) { acceptedOwner, acceptedVersion ->
                refreshResults.publish(AttendanceRefreshScope(month, actualFrom, actualTo), acceptedOwner, acceptedVersion, result, origin)
                result
            } ?: AttendanceRefreshResult.Superseded
        }

    override fun observeDay(date: LocalDate): Flow<AttendanceRecord?> =
        sessionManager.state
            .map { it.accountHashOf(SchoolSystem.STU) }
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
            .map { it.accountHashOf(SchoolSystem.STU) }
            .distinctUntilChanged()
            .flatMapLatest { hash ->
                if (hash.isNullOrBlank()) {
                    flowOf(AttendanceMonth(month))
                } else {
                    kotlinx.coroutines.flow.combine(
                        attendanceDao.observeMonth(hash, month),
                        attendanceMetaDao.observe(hash, month)
                    ) { rows, meta -> rows.toMonth(month, meta) }
                }
            }

    override fun observePunches(from: LocalDate, to: LocalDate): Flow<List<AttendancePunch>> =
        sessionManager.state
            .map { it.accountHashOf(SchoolSystem.STU) }
            .distinctUntilChanged()
            .flatMapLatest { hash ->
                if (hash.isNullOrBlank()) {
                    flowOf(emptyList())
                } else {
                    punchDao.observeRange(hash, from, to).map { rows -> rows.map { it.toDomain() } }
                }
            }

    override suspend fun refreshPunches(from: LocalDate, to: LocalDate): AttendanceRefreshResult =
        refreshPunchRange(from, to)

    private suspend fun refreshPunchRange(from: LocalDate, to: LocalDate, evidence: RefreshEvidence? = null): AttendanceRefreshResult =
        withContext(io) {
            punchMutex.withLock {
                val accountHash = sessionManager.requireAccountHash(SchoolSystem.STU)
                val accountVersion = sessionManager.accountVersion(SchoolSystem.STU)
                val authenticationVersion = sessionManager.authenticationVersion(SchoolSystem.STU)
                evidence?.authenticationVersion = authenticationVersion
                val attemptedAt = System.currentTimeMillis()

                if (!networkMonitor.hasNetwork) {
                    val cached = punchDao.countRange(accountHash, from, to) > 0
                    return@withLock if (sessionManager.responseIsCurrent(SchoolSystem.STU, accountVersion, authenticationVersion))
                        AttendanceRefreshResult.Offline(cached) else AttendanceRefreshResult.Superseded
                }

                val baseUrl = StuConfig.baseUrlOrDefault(sessionStore.baseUrl(SchoolSystem.STU))
                var outcome = punchDataSource.fetchPunches(baseUrl, from, to)
                if (outcome.result is RemoteResult.SessionExpired &&
                    sessionManager.responseIsCurrent(SchoolSystem.STU, accountVersion, authenticationVersion) &&
                    stuRemote.renewSession(baseUrl, canContinue = {
                        sessionManager.responseIsCurrent(SchoolSystem.STU, accountVersion, authenticationVersion)
                    })) {
                    outcome = punchDataSource.fetchPunches(baseUrl, from, to)
                }
                AppLog.d("punch fetch $from..$to: ${outcome.result.describe()}")

                if (outcome.result !is RemoteResult.Success &&
                    !sessionManager.responseIsCurrent(SchoolSystem.STU, accountVersion, authenticationVersion)) {
                    return@withLock AttendanceRefreshResult.Superseded
                }

                val refreshResult = when (val result = outcome.result) {
                    is RemoteResult.Success -> {
                        sessionManager.withAccount(SchoolSystem.STU, null, accountVersion) { owner ->
                            val rows = result.data.map { it.toEntity(owner, attemptedAt) }
                            punchDao.replaceRange(owner, from, to, rows)
                            sessionManager.set(SchoolSystem.STU, SessionState.AUTHENTICATED)
                            evidence?.authenticationVersion = sessionManager.authenticationVersion(SchoolSystem.STU)
                            AppLog.i("punches synced $from..$to: ${rows.size} record(s)")
                            AttendanceRefreshResult.Success(to.toString(), rows.size)
                        } ?: AttendanceRefreshResult.Superseded
                    }
                    RemoteResult.SessionExpired -> {
                        if (!sessionManager.set(SchoolSystem.STU, SessionState.EXPIRED, accountVersion, authenticationVersion)) {
                            return@withLock AttendanceRefreshResult.Superseded
                        }
                        AttendanceRefreshResult.SessionExpired
                    }
                    is RemoteResult.NetworkUnavailable -> {
                        networkFailureResult(networkMonitor.hasNetwork, punchDao.countRange(accountHash, from, to) > 0, result.reason)
                    }
                    is RemoteResult.SchemaChanged -> {
                        AttendanceRefreshResult.SchemaChanged(result.reason)
                    }
                    is RemoteResult.ServerError -> {
                        AttendanceRefreshResult.ServerError(result.code)
                    }
                    is RemoteResult.UnknownError -> {
                        AttendanceRefreshResult.Failed(result.reason)
                    }
                }
                if (!refreshResult.isSuccess &&
                    !sessionManager.responseIsCurrent(SchoolSystem.STU, accountVersion, authenticationVersion))
                    AttendanceRefreshResult.Superseded else refreshResult
            }
        }

    private fun AttendancePunch.toEntity(accountHash: String, updatedAt: Long) = AttendancePunchEntity(
        id = "$accountHash|$id",
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

    override suspend fun refresh(month: String): AttendanceRefreshResult = refreshMonth(month)

    private suspend fun refreshMonth(month: String, evidence: RefreshEvidence? = null): AttendanceRefreshResult = withContext(io) {
        refreshMutex.withLock {
            if (!StuAttendanceDataSource.isValidMonth(month)) {
                return@withLock AttendanceRefreshResult.Failed("月份格式应为 YYYY-MM")
            }
            val accountHash = sessionManager.requireAccountHash(SchoolSystem.STU)
            val accountVersion = sessionManager.accountVersion(SchoolSystem.STU)
            val authenticationVersion = sessionManager.authenticationVersion(SchoolSystem.STU)
            evidence?.authenticationVersion = authenticationVersion
            val attemptedAt = System.currentTimeMillis()

            if (!networkMonitor.hasNetwork) {
                recordAttempt(accountHash, month, attemptedAt, "offline")
                val cached = hasCache(accountHash, month)
                return@withLock if (sessionManager.responseIsCurrent(SchoolSystem.STU, accountVersion, authenticationVersion))
                    AttendanceRefreshResult.Offline(cached) else AttendanceRefreshResult.Superseded
            }

            val baseUrl = StuConfig.baseUrlOrDefault(sessionStore.baseUrl(SchoolSystem.STU))
            var outcome = dataSource.fetchMonth(baseUrl, month)
            if (outcome.result is RemoteResult.SessionExpired &&
                sessionManager.responseIsCurrent(SchoolSystem.STU, accountVersion, authenticationVersion) &&
                stuRemote.renewSession(baseUrl, canContinue = {
                    sessionManager.responseIsCurrent(SchoolSystem.STU, accountVersion, authenticationVersion)
                })) {
                outcome = dataSource.fetchMonth(baseUrl, month)
            }
            AppLog.d("attendance fetch $month: ${outcome.result.describe()}")

            if (outcome.result !is RemoteResult.Success &&
                !sessionManager.responseIsCurrent(SchoolSystem.STU, accountVersion, authenticationVersion)) {
                return@withLock AttendanceRefreshResult.Superseded
            }

            val refreshResult = when (val result = outcome.result) {
                is RemoteResult.Success -> {
                    sessionManager.withAccount(SchoolSystem.STU, result.data.accountHash, accountVersion) { owner ->
                        val rows = result.data.records.map { it.toEntity(owner, month, attemptedAt) }
                        val oldMeta = attendanceMetaDao.all(owner).firstOrNull { it.month == month }
                        val period = StuAttendancePeriodParser.fromWeeks(month, result.data.weeks)
                        attendanceDao.replaceMonth(owner, month, rows)
                        attendanceMetaDao.upsert(
                            AttendanceMetaEntity(
                                accountHash = owner,
                                month = month,
                                lastSuccessAt = System.currentTimeMillis(),
                                lastAttemptAt = attemptedAt,
                                lastError = null,
                                stats = result.data.stats.entries.joinToString(";") { "${it.key}=${it.value}" },
                                periodWeeks = period?.let(StuAttendancePeriodParser::encode) ?: oldMeta?.periodWeeks,
                                periodUpdatedAt = if (period != null) timeProvider.epochMillis() else oldMeta?.periodUpdatedAt
                            )
                        )
                        sessionManager.set(SchoolSystem.STU, SessionState.AUTHENTICATED)
                        evidence?.authenticationVersion = sessionManager.authenticationVersion(SchoolSystem.STU)
                        AppLog.i("attendance synced $month: ${rows.size} day(s)")
                        AttendanceRefreshResult.Success(month, rows.size)
                    } ?: AttendanceRefreshResult.Superseded
                }

                RemoteResult.SessionExpired -> {
                    if (!sessionManager.set(SchoolSystem.STU, SessionState.EXPIRED, accountVersion, authenticationVersion)) {
                        return@withLock AttendanceRefreshResult.Superseded
                    }
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
            if (!refreshResult.isSuccess &&
                !sessionManager.responseIsCurrent(SchoolSystem.STU, accountVersion, authenticationVersion))
                AttendanceRefreshResult.Superseded else refreshResult
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
        meta: AttendanceMetaEntity?
    ): AttendanceMonth {
        val stats = meta?.stats.orEmpty()
            .split(';')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val summary = StuAttendanceParser.toSummary(stats)
        val schoolWeeks = StuAttendancePeriodParser.decode(meta?.periodWeeks, month)?.weeks.orEmpty()
        val schoolDates = schoolWeeks.flatMap { week ->
            val start = requireNotNull(week.start)
            (0..6L).map(start::plusDays)
        }.toSet()
        val confirmedWeeks = schoolWeeks.map { week -> week.copy(records = filter { it.date in requireNotNull(week.start)..requireNotNull(week.end) }.map { it.toDomain() }) }
        val byWeek = filter { it.date !in schoolDates }.groupBy { row ->
            row.date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        }
        val weeks = confirmedWeeks + byWeek.entries.sortedBy { it.key }.map { (monday, rows) ->
            AttendanceWeek(
                label = "${monday.monthValue}月${monday.dayOfMonth}日起",
                range = "$monday,${monday.plusDays(6)}",
                records = rows.sortedBy { it.date }.map { it.toDomain() }
            )
        }
        return AttendanceMonth(month = month, weeks = weeks, stats = stats, summary = summary)
    }
}
