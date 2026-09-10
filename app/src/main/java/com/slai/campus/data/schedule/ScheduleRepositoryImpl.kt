package com.slai.campus.data.schedule

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.Redactor
import com.slai.campus.core.common.IntegrationMode
import com.slai.campus.core.common.IoDispatcher
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.common.describe
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.core.database.CourseOccurrenceEntity
import com.slai.campus.core.database.ScheduleDao
import com.slai.campus.core.database.SyncMetaEntity
import com.slai.campus.core.database.SyncMetaDao
import com.slai.campus.core.network.NetworkMonitor
import com.slai.campus.core.session.SessionManager
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.session.SessionStore
import com.slai.campus.data.sis.SisConfig
import com.slai.campus.data.sis.SisRemoteDataSource
import com.slai.campus.data.sis.SisScheduleParser
import com.slai.campus.data.sis.SisTimetableParsed
import com.slai.campus.data.sis.SisWebViewDataSource
import com.slai.campus.data.provider.GenericTimetableParser
import com.slai.campus.data.provider.ProviderExecutor
import com.slai.campus.data.provider.ProviderStore
import com.slai.campus.domain.schedule.ClassOccurrence
import com.slai.campus.domain.schedule.RefreshPhase
import com.slai.campus.domain.schedule.RefreshReason
import com.slai.campus.domain.schedule.RefreshResult
import com.slai.campus.domain.schedule.ScheduleRepository
import com.slai.campus.domain.schedule.ScheduleSource
import com.slai.campus.domain.schedule.Semester
import com.slai.campus.domain.schedule.SyncState
import com.slai.campus.domain.provider.ProviderPurpose
import com.slai.campus.reminder.ReminderScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The closed loop from the design plan.
 *
 * Read path (offline-first):
 * ```
 * Room -> Flow<...> -> UI          (never touches the network)
 * ```
 *
 * Write path (only ever replaces data that passed full validation):
 * ```
 * refresh()
 *   -> no network?                keep cache, report Offline
 *   -> discover semester/term
 *   -> native ZFSoft JSON API     Success -> atomic replace, reschedule reminders
 *        SessionExpired           -> NEEDS_LOGIN, keep cache
 *        SchemaChanged/5xx        -> fall through to WebView extraction
 *   -> WebView extraction         Success -> atomic replace (or per-date replace for DOM)
 *        SessionExpired           -> NEEDS_LOGIN, keep cache
 *   -> otherwise                  keep cache, report the classified failure
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ScheduleRepositoryImpl @Inject constructor(
    private val scheduleDao: ScheduleDao,
    private val syncMetaDao: SyncMetaDao,
    private val sessionManager: SessionManager,
    private val sessionStore: SessionStore,
    private val sisRemote: SisRemoteDataSource,
    private val sisWebView: SisWebViewDataSource,
    private val providerStore: ProviderStore,
    private val providerExecutor: ProviderExecutor,
    private val networkMonitor: NetworkMonitor,
    private val timeProvider: TimeProvider,
    private val reminderScheduler: ReminderScheduler,
    @IoDispatcher private val io: CoroutineDispatcher
) : ScheduleRepository {

    private val lastRefresh = MutableStateFlow<RefreshResult?>(null)
    private val refreshing = MutableStateFlow(false)
    private val phase = MutableStateFlow(RefreshPhase.IDLE)

    /** Ring buffer of human-readable steps for the most recent refresh. */
    private val refreshTrace = MutableStateFlow<List<String>>(emptyList())

    private fun trace(line: String) {
        AppLog.i("refresh: $line")
        refreshTrace.value = (refreshTrace.value + line).takeLast(60)
    }

    /**
     * Serialises refreshes.
     *
     * The previous boolean guard had a nasty failure mode: the app-start background sync would set
     * the flag, spend minutes walking every candidate endpoint, and meanwhile the home screen showed a
     * spinner with the refresh button *disabled* — indistinguishable from a freeze. A mutex makes a
     * concurrent caller wait and then run its own refresh, which is what a user tapping 刷新 expects.
     */
    private val refreshMutex = Mutex()
    private var lastCompletedAt = 0L

    override fun observeToday(): Flow<List<ClassOccurrence>> =
        observeRange(timeProvider.today(), timeProvider.today())

    override fun observeWeek(): Flow<List<ClassOccurrence>> {
        val monday = timeProvider.today()
            .with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        return observeRange(monday, monday.plusDays(6))
    }

    override fun observeRange(from: LocalDate, to: LocalDate): Flow<List<ClassOccurrence>> =
        sessionManager.state
            .map { it.accountHash }
            .distinctUntilChanged()
            .flatMapLatest { hash ->
                if (hash.isNullOrBlank()) {
                    flowOf(emptyList())
                } else {
                    scheduleDao.observeRange(hash, from, to).map { rows -> rows.map { it.toDomain() } }
                }
            }

    override fun observeSyncState(): Flow<SyncState> =
        sessionManager.state
            .map { it.accountHash }
            .distinctUntilChanged()
            .flatMapLatest { hash ->
                if (hash.isNullOrBlank()) {
                    flowOf(SyncState(system = SchoolSystem.SIS))
                } else {
                    syncMetaDao.observe(hash, SchoolSystem.SIS.key).map { meta ->
                        SyncState(
                            system = SchoolSystem.SIS,
                            lastSuccessAt = meta?.lastSuccessAt?.let(java.time.Instant::ofEpochMilli),
                            lastAttemptAt = meta?.lastAttemptAt?.let(java.time.Instant::ofEpochMilli),
                            parserVersion = meta?.parserVersion,
                            integrationMode = meta?.integrationMode
                                ?.let { runCatching { IntegrationMode.valueOf(it) }.getOrNull() }
                        )
                    }
                }
            }

    override fun observeLastRefresh(): Flow<RefreshResult?> = lastRefresh.asStateFlow()

    override fun observeIsRefreshing(): Flow<Boolean> = refreshing.asStateFlow()

    override fun observePhase(): Flow<RefreshPhase> = phase.asStateFlow()

    override fun observeRefreshTrace(): Flow<List<String>> = refreshTrace.asStateFlow()

    override suspend fun snapshotUpcoming(from: LocalDate, days: Int): List<ClassOccurrence> =
        withContext(io) {
            val hash = sessionManager.current().accountHash ?: return@withContext emptyList()
            scheduleDao.rangeOnce(hash, from, from.plusDays(days.toLong() - 1)).map { it.toDomain() }
        }

    // ---------------------------------------------------------------------------------------
    // Refresh
    // ---------------------------------------------------------------------------------------

    override suspend fun refresh(reason: RefreshReason): RefreshResult = withContext(io) {
        refreshMutex.withLock {
            // Collapse a double tap / an app-start refresh into one request.
            val since = System.currentTimeMillis() - lastCompletedAt
            val previous = lastRefresh.value
            if (reason == RefreshReason.MANUAL && previous is RefreshResult.Success && since < 3_000) {
                return@withLock previous
            }

            refreshing.value = true
            try {
                val result = doRefresh()
                lastRefresh.value = result
                result
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("refresh crashed: ${e.javaClass.simpleName}")
                val failure = RefreshResult.Failed(e.javaClass.simpleName)
                lastRefresh.value = failure
                failure
            } finally {
                lastCompletedAt = System.currentTimeMillis()
                refreshing.value = false
                phase.value = RefreshPhase.IDLE
            }
        }
    }

    private suspend fun doRefresh(): RefreshResult {
        val accountHash = sessionManager.requireAccountHash()
        val attemptedAt = System.currentTimeMillis()
        refreshTrace.value = emptyList()
        trace("start: accountHash=${accountHash.take(8)}… today=${timeProvider.today()}")

        if (!networkMonitor.hasNetwork) {
            trace("no network -> offline")
            recordAttempt(accountHash, attemptedAt, "offline")
            return RefreshResult.Offline(cached = hasCache(accountHash))
        }

        val baseUrl = SisConfig.baseUrlOrDefault(sessionStore.baseUrl(SchoolSystem.SIS))
        trace("baseUrl=$baseUrl")

        // Everything below shares one deadline. Without it, three dead candidate endpoints at the
        // per-call timeout add up to minutes, which is what made the first version look frozen.
        val deadline = System.currentTimeMillis() + NETWORK_PHASE_BUDGET_MS

        var lastProviderNote: String? = null

        val knownProviders = runCatching {
            providerStore.enabledFor(SchoolSystem.SIS, ProviderPurpose.TIMETABLE)
        }.getOrDefault(emptyList())
        trace("providers=${knownProviders.size}${knownProviders.joinToString("") { " [${it.name}]" }}")

        val (storedYear, storedTerm) = sessionStore.semesterCodes()
        val (storedAnchor, anchorConfirmed) = sessionStore.semesterAnchor()
        trace("stored: year=$storedYear term=$storedTerm anchor=$storedAnchor confirmed=$anchorConfirmed")

        // A learned provider plus a confirmed anchor means we already know the term: skip the page
        // round-trip entirely so the common case is one request.
        val canSkipDiscovery = knownProviders.isNotEmpty() &&
            !storedYear.isNullOrBlank() && !storedTerm.isNullOrBlank() && anchorConfirmed && storedAnchor != null

        val semester: Semester = if (canSkipDiscovery) {
            trace("semester: using STORED term/anchor (discovery skipped)")
            Semester(
                academicYear = storedYear,
                termCode = storedTerm,
                displayName = Semester.termDisplayName(storedTerm),
                firstWeekMonday = storedAnchor
            )
        } else {
            phase.value = RefreshPhase.DISCOVERING

            // The school answers this with the current term, so no page scraping is needed.
            // The probe also reports *why* it failed: 901 / 302-to-login means the session is gone,
            // which must stop the refresh here instead of silently falling back to a guessed term
            // (that fallback is what used to turn "please log in again" into a permanent
            // "课表拉不到" with no explanation).
            var probe = runCatching { sisRemote.probeCurrentSemester(baseUrl) }
                .getOrElse { com.slai.campus.data.sis.SemesterProbe(null, null, false, null) }
            trace("probe term: status=${probe.status} expired=${probe.sessionExpired} " +
                "xnm=${probe.term?.xnm} xqm=${probe.term?.xqm} week=${probe.term?.week}")

            if (probe.sessionExpired) {
                trace("session gone; attempting silent SSO renewal")
                // Usually only the *application* session timed out while AD FS still remembers the
                // user, in which case walking the SSO chain silently gets a new one — no typing.
                val refreshed = trySilentRenewal(baseUrl)
                if (refreshed != null) {
                    probe = refreshed
                    trace("silent renewal OK: xnm=${refreshed.term?.xnm} xqm=${refreshed.term?.xqm}")
                } else {
                    trace("silent renewal failed -> 需要重新登录")
                    sessionManager.set(SchoolSystem.SIS, SessionState.EXPIRED)
                    recordAttempt(accountHash, attemptedAt, "session expired (current-semester probe)")
                    return RefreshResult.SessionExpired
                }
            }
            val fromServer = probe.term

            val fallback = com.slai.campus.data.sis.SemesterCalendarResolver
                .estimateTermFromDate(timeProvider.today())
            val year = fromServer?.xnm ?: storedYear ?: fallback.first
            val term = fromServer?.xqm ?: storedTerm ?: fallback.second
            val fromWeek = fromServer?.week?.let {
                com.slai.campus.data.sis.SemesterCalendarResolver.fromObservedWeek(it, timeProvider.today())
            }
            trace("term: year=$year term=$term (fromServer=${fromServer != null}) fromWeek=$fromWeek")

            // Page discovery is still attempted, but only to find the week anchor; a failure here no
            // longer blocks the sync.
            val discovery = runCatching { sisRemote.discoverSemester(baseUrl, deadline) }
                .getOrElse { com.slai.campus.data.sis.SemesterDiscovery() }
            trace("discovery: needsLogin=${discovery.needsLogin} pageAnchor=${discovery.semester.firstWeekMonday}")

            if (discovery.needsLogin && fromServer == null) {
                trace("discovery says logged out -> 需要重新登录")
                sessionManager.set(SchoolSystem.SIS, SessionState.EXPIRED)
                recordAttempt(accountHash, attemptedAt, "session expired during discovery")
                return RefreshResult.SessionExpired
            }

            /*
             * Anchor priority, identical to `SisRemoteDataSource.resolveSemester`.
             *
             * These two used to disagree: the refresh preferred the *stored* anchor unconditionally,
             * while the diagnostics path preferred the freshly observed week. That is how the
             * diagnostic could report a perfect success (correct anchor, 84 occurrences) while the
             * real refresh silently expanded the whole timetable onto the wrong dates and the current
             * week came out empty.
             */
            val anchor = when {
                anchorConfirmed && storedAnchor != null -> storedAnchor
                fromWeek != null -> fromWeek
                discovery.semester.firstWeekMonday != null -> discovery.semester.firstWeekMonday
                storedAnchor != null -> storedAnchor
                else -> com.slai.campus.data.sis.SemesterCalendarResolver.estimate(year, term)
            }
            trace("anchor(week-1 Monday)=$anchor  [source=${when {
                anchorConfirmed && storedAnchor != null -> "stored+confirmed"
                fromWeek != null -> "server week"
                discovery.semester.firstWeekMonday != null -> "page select"
                storedAnchor != null -> "stored"
                else -> "estimate"
            }}]")

            if (!storedYear.isNullOrBlank() || fromServer != null) {
                sessionStore.setSemesterCodes(year, term)
            }
            if (anchor != null) {
                sessionStore.setSemesterAnchor(anchor, confirmed = anchorConfirmed || fromWeek != null)
            }

            Semester(
                academicYear = year,
                termCode = term,
                displayName = Semester.termDisplayName(term),
                firstWeekMonday = anchor
            )
        }
        trace("semester resolved: $semester")

        // 2) Configured / auto-learned providers run first: they encode the *real* endpoint, whereas
        //    the built-in candidates are only a best guess at the vendor's default paths.
        phase.value = RefreshPhase.READING_PROVIDER
        for (provider in knownProviders) {
            if (System.currentTimeMillis() > deadline) break
            val response = providerExecutor.execute(provider, semester)
            trace("provider ${provider.name}: status=${response.status} ${response.durationMs}ms")

            if (response.error != null) continue
            if (response.status == 401 || response.status == 403) {
                sessionManager.set(SchoolSystem.SIS, SessionState.EXPIRED)
                recordAttempt(accountHash, attemptedAt, "session expired (provider)")
                return RefreshResult.SessionExpired
            }
            if (response.status in 300..399) {
                if (SisScheduleParser.isLoginUrl(response.finalUrl)) {
                    sessionManager.set(SchoolSystem.SIS, SessionState.EXPIRED)
                    recordAttempt(accountHash, attemptedAt, "session expired (provider redirect)")
                    return RefreshResult.SessionExpired
                }
                continue
            }
            if (response.isRefused) {
                AppLog.i("provider ${provider.name} refused with 901")
                lastProviderNote = "服务器返回 901（未授权或请求被拦截）"
                continue
            }
            if (!response.isSuccess) continue
            if (SisScheduleParser.looksLikeLoginPage(response.body)) {
                sessionManager.set(SchoolSystem.SIS, SessionState.EXPIRED)
                recordAttempt(accountHash, attemptedAt, "session expired (provider login page)")
                return RefreshResult.SessionExpired
            }

            val parsed = GenericTimetableParser.parse(
                body = response.body,
                provider = provider,
                semester = semester,
                accountHash = accountHash,
                source = ScheduleSource.SIS_PROVIDER
            )
            if (parsed is RemoteResult.Success) {
                /*
                 * A provider that "succeeds" with zero rows must NOT be allowed to end the refresh.
                 *
                 * `GenericTimetableParser` treats an empty array at `rowsPath` as a genuine empty
                 * timetable and returns Success(occurrences = []). Persisting that calls
                 * `replaceAll`, which deletes every cached row and inserts nothing — so the home
                 * screen said 「已同步」 while the week view went empty, and it happened again on
                 * every refresh. Falling through lets the built-in ZFSoft path, which is known to
                 * return the real 6 courses, have its turn.
                 */
                if (parsed.data.occurrences.isEmpty()) {
                    trace("provider ${provider.name}: 0 rows -> ignoring it, continuing")
                    continue
                }
                sessionManager.set(SchoolSystem.SIS, SessionState.AUTHENTICATED)
                return persist(
                    accountHash, parsed.data, IntegrationMode.NATIVE_API, attemptedAt, fullSemester = true
                )
            }
            trace("provider ${provider.name} rejected: ${parsed.describe()}")
        }

        // 3) Built-in ZFSoft candidates.
        phase.value = RefreshPhase.NATIVE
        // `sjkList` is empty on this deployment, so the period times come from their own endpoint.
        val periodTimes = runCatching {
            sisRemote.fetchPeriodTimes(
                baseUrl,
                semester.academicYear.orEmpty(),
                semester.termCode.orEmpty()
            )
        }.getOrDefault(emptyList())
        trace("period times: ${periodTimes.size} slots")
        if (lastProviderNote != null) trace("provider note: $lastProviderNote")
        val native = runCatching {
            sisRemote.fetchTimetable(baseUrl, semester, accountHash, deadline, periodTimes)
        }
            .getOrElse { error ->
                trace("native fetch threw: ${error.javaClass.simpleName}")
                SisRemoteDataSource.FetchOutcome(
                    RemoteResult.UnknownError(error.javaClass.simpleName), emptyList(), null
                )
            }
        native.traces.forEach { t ->
            trace("native ${t.method} ${Redactor.redactUrl(t.url)} -> status=${t.status} ${t.durationMs}ms")
        }

        when (val result = native.result) {
            is RemoteResult.Success -> {
                sessionManager.set(SchoolSystem.SIS, SessionState.AUTHENTICATED)
                return persist(accountHash, result.data, IntegrationMode.NATIVE_API, attemptedAt, fullSemester = true)
            }
            RemoteResult.SessionExpired -> {
                // Reaching here with an expired session means discovery was skipped (a learned
                // provider plus a confirmed anchor), so this is the first sign the session died.
                // Give the silent SSO renewal one shot before asking the user for anything.
                if (trySilentRenewal(baseUrl) != null) {
                    val retry = runCatching {
                        sisRemote.fetchTimetable(baseUrl, semester, accountHash, deadline, periodTimes)
                    }.getOrNull()
                    val retried = retry?.result
                    if (retried is RemoteResult.Success) {
                        sessionManager.set(SchoolSystem.SIS, SessionState.AUTHENTICATED)
                        return persist(
                            accountHash, retried.data, IntegrationMode.NATIVE_API,
                            attemptedAt, fullSemester = true
                        )
                    }
                }
                sessionManager.set(SchoolSystem.SIS, SessionState.EXPIRED)
                recordAttempt(accountHash, attemptedAt, "session expired")
                return RefreshResult.SessionExpired
            }
            else -> trace("native failed: ${result.describe()} -> trying WebView extraction")
        }

        // 4) WebView extraction.
        phase.value = RefreshPhase.WEBVIEW
        val webResult = runCatching { sisWebView.fetchTimetable(baseUrl, semester, accountHash) }
            .getOrElse { error ->
                trace("webview fetch threw: ${error.javaClass.simpleName}")
                RemoteResult.UnknownError(error.javaClass.simpleName)
            }

        when (webResult) {
            is RemoteResult.Success -> {
                sessionManager.set(SchoolSystem.SIS, SessionState.AUTHENTICATED)
                val full = webResult.data.occurrences.all { it.source == ScheduleSource.SIS_WEBVIEW_XHR }
                return persist(
                    accountHash,
                    webResult.data,
                    IntegrationMode.WEBVIEW_EXTRACT,
                    attemptedAt,
                    fullSemester = full
                )
            }
            RemoteResult.SessionExpired -> {
                sessionManager.set(SchoolSystem.SIS, SessionState.EXPIRED)
                recordAttempt(accountHash, attemptedAt, "session expired during extraction")
                return RefreshResult.SessionExpired
            }
            else -> Unit
        }

        // 5) Everything failed: classify and keep the cache untouched.
        val failure = when (val nativeFailure = native.result) {
            is RemoteResult.NetworkUnavailable -> RefreshResult.Offline(cached = hasCache(accountHash))
            is RemoteResult.ServerError -> RefreshResult.ServerError(nativeFailure.code)
            is RemoteResult.SchemaChanged -> RefreshResult.SchemaChanged(nativeFailure.reason)
            is RemoteResult.UnknownError -> RefreshResult.Failed(nativeFailure.reason)
            else -> when (webResult) {
                is RemoteResult.SchemaChanged -> RefreshResult.SchemaChanged(webResult.reason)
                is RemoteResult.UnknownError -> RefreshResult.Failed(webResult.reason)
                else -> RefreshResult.Failed("unknown")
            }
        }
        trace("ALL PATHS FAILED -> $failure")
        recordAttempt(accountHash, attemptedAt, failure.toString())
        return failure
    }

    /**
     * One silent SSO renewal attempt, confirmed by a fresh term probe.
     *
     * Returns the new probe on success (the session really works again), or null when the SSO itself
     * wants credentials — in which case only the user can help.
     */
    private suspend fun trySilentRenewal(
        baseUrl: String
    ): com.slai.campus.data.sis.SemesterProbe? {
        val walked = runCatching { sisRemote.renewSession(baseUrl) }.getOrDefault(false)
        if (!walked) return null
        val probe = runCatching { sisRemote.probeCurrentSemester(baseUrl) }
            .getOrNull() ?: return null
        return if (!probe.sessionExpired && probe.term != null) {
            AppLog.i("silent renewal succeeded: session is alive again")
            probe
        } else {
            AppLog.i("silent renewal walked the SSO but the session is still not usable")
            null
        }
    }

    /** Persists a validated payload; this is the only place cached rows are replaced. */
    private suspend fun persist(
        accountHash: String,
        parsed: SisTimetableParsed,
        mode: IntegrationMode,
        attemptedAt: Long,
        fullSemester: Boolean
    ): RefreshResult {
        phase.value = RefreshPhase.SAVING
        val rows = parsed.occurrences.map { it.toEntity(accountHash, attemptedAt) }
        trace("persist: ${rows.size} row(s) mode=$mode fullSemester=$fullSemester " +
            "rawItems=${parsed.rawItemCount} skipped=${parsed.skippedRows}")

        /*
         * A success with zero rows must never clear the cache.
         *
         * `replaceAll` deletes first, so accepting an empty payload would silently wipe a perfectly
         * good timetable and then report 「已同步」 — the user sees an empty week and no error
         * anywhere. "Confirmed empty" is already handled upstream: `SisScheduleParser` only reports
         * Success when the payload looks like a real timetable response.
         */
        if (rows.isEmpty()) {
            trace("refusing to persist 0 rows; keeping the previous cache")
            recordAttempt(accountHash, attemptedAt, "payload contained 0 rows")
            return RefreshResult.SchemaChanged("服务器返回了 0 条课程（已保留原有缓存）")
        }

        if (fullSemester) {
            scheduleDao.replaceAll(accountHash, rows)
        } else {
            val dates = parsed.occurrences.map { it.date }.distinct()
            scheduleDao.replaceDates(accountHash, dates, rows)
        }

        syncMetaDao.upsert(
            SyncMetaEntity(
                accountHash = accountHash,
                system = SchoolSystem.SIS.key,
                lastSuccessAt = System.currentTimeMillis(),
                lastAttemptAt = attemptedAt,
                parserVersion = SisConfig.PARSER_VERSION,
                lastError = parsed.warning,
                integrationMode = mode.name,
                semesterFirstWeekMonday = null
            )
        )
        sessionStore.setIntegrationMode(SchoolSystem.SIS, mode)

        // Self-correcting: the payload says which term it answered for.
        parsed.reportedTerm?.let { (year, term) ->
            sessionStore.setSemesterCodes(year, term)
        }

        // A discovered student id re-partitions the cache; the rows just written follow the new key.
        if (parsed.studentId != null) {
            sessionManager.onStudentIdDiscovered(parsed.studentId)
        }

        trace("synced: ${rows.size} rows via $mode (fullSemester=$fullSemester)")
        if (rows.isNotEmpty()) {
            trace("dates: ${rows.minOf { it.date }} … ${rows.maxOf { it.date }}")
        }

        runCatching { reminderScheduler.rescheduleFromCache() }
            .onFailure { AppLog.w("reminder reschedule failed: ${it.javaClass.simpleName}") }

        return RefreshResult.Success(added = rows.size, total = rows.size)
    }

    private suspend fun recordAttempt(accountHash: String, attemptedAt: Long, error: String) {
        val existing = syncMetaDao.get(accountHash, SchoolSystem.SIS.key)
        syncMetaDao.upsert(
            (existing ?: SyncMetaEntity(
                accountHash = accountHash,
                system = SchoolSystem.SIS.key,
                lastSuccessAt = null,
                lastAttemptAt = null,
                parserVersion = null,
                lastError = null,
                integrationMode = null,
                semesterFirstWeekMonday = null
            )).copy(
                lastAttemptAt = attemptedAt,
                lastError = error
            )
        )
    }

    private suspend fun hasCache(accountHash: String): Boolean = scheduleDao.count(accountHash) > 0

    companion object {
        /** Hard ceiling for the whole network phase of one refresh. */
        private const val NETWORK_PHASE_BUDGET_MS = 75_000L
    }

    private fun ClassOccurrence.toEntity(accountHash: String, updatedAt: Long) = CourseOccurrenceEntity(
        id = id,
        accountHash = accountHash,
        courseName = courseName,
        teacher = teacher,
        location = location,
        date = date,
        startTime = startTime,
        endTime = endTime,
        source = source.name,
        weekIndex = weekIndex,
        periodStart = periodStart,
        periodEnd = periodEnd,
        courseCode = courseCode,
        teachingClass = teachingClass,
        campus = campus,
        updatedAt = updatedAt
    )

    private fun CourseOccurrenceEntity.toDomain() = ClassOccurrence(
        id = id,
        courseName = courseName,
        teacher = teacher,
        location = location,
        date = date,
        startTime = startTime,
        endTime = endTime,
        source = runCatching { ScheduleSource.valueOf(source) }.getOrDefault(ScheduleSource.SIS_NATIVE),
        weekIndex = weekIndex,
        periodStart = periodStart,
        periodEnd = periodEnd,
        courseCode = courseCode,
        teachingClass = teachingClass,
        campus = campus
    )
}
