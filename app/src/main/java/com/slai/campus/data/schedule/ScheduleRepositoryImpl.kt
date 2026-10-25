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
import com.slai.campus.data.sis.resolveSemesterAnchor
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
        timeProvider.observeDate().flatMapLatest { date -> observeRange(date, date) }

    override fun observeRange(from: LocalDate, to: LocalDate): Flow<List<ClassOccurrence>> =
        sessionManager.state
            .map { it.accountHashOf(SchoolSystem.SIS) }
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
            .map { it.accountHashOf(SchoolSystem.SIS) }
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
        val accountHash = sessionManager.requireAccountHash(SchoolSystem.SIS)
        val accountVersion = sessionManager.accountVersion(SchoolSystem.SIS)
        val owner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val canContinue = { owner?.isActive != false && sessionManager.accountVersion(SchoolSystem.SIS) == accountVersion }
        val authenticationVersion = sessionManager.authenticationVersion(SchoolSystem.SIS)
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

        val storedConfiguration = sessionStore.semesterConfiguration()
        val storedYear = storedConfiguration.year
        val storedTerm = storedConfiguration.term
        val (storedAnchor, anchorConfirmed) = storedConfiguration.anchorFor(storedYear, storedTerm)
        trace("stored: year=$storedYear term=$storedTerm anchor=$storedAnchor confirmed=$anchorConfirmed")

        // A cached term is reusable for one day, even when its anchor was observed rather than
        // user-confirmed. A new term cannot inherit the previous term's confirmation.
        val canSkipDiscovery = knownProviders.isNotEmpty() && storedConfiguration.canReuse(attemptedAt)

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
                val refreshed = trySilentRenewal(baseUrl) { sessionManager.responseIsCurrent(SchoolSystem.SIS, accountVersion, authenticationVersion) }
                if (refreshed != null) {
                    probe = refreshed
                    trace("silent renewal OK: xnm=${refreshed.term?.xnm} xqm=${refreshed.term?.xqm}")
                } else {
                    trace("silent renewal failed -> 需要重新登录")
                    if (!sessionManager.set(SchoolSystem.SIS, SessionState.EXPIRED, accountVersion, authenticationVersion)) return RefreshResult.Failed("会话已更新，已忽略旧响应；请刷新")
                    recordAttempt(accountHash, attemptedAt, "session expired (current-semester probe)")
                    return RefreshResult.SessionExpired
                }
            }
            val fromServer = probe.term

            val fallback = com.slai.campus.data.sis.SemesterCalendarResolver
                .estimateTermFromDate(timeProvider.today())
            val fromWeek = fromServer?.week?.let {
                com.slai.campus.data.sis.SemesterCalendarResolver.fromObservedWeek(it, timeProvider.today())
            }

            // The current-term endpoint usually also supplies the week; avoid a redundant page walk.
            val discovery = if (fromServer != null && (fromWeek != null ||
                    storedConfiguration.anchorFor(fromServer.xnm, fromServer.xqm).first != null)) {
                com.slai.campus.data.sis.SemesterDiscovery()
            } else {
                runCatching { sisRemote.discoverSemester(baseUrl, deadline, canContinue) }
                    .getOrElse {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        com.slai.campus.data.sis.SemesterDiscovery()
                    }
            }
            trace("discovery: needsLogin=${discovery.needsLogin} pageAnchor=${discovery.semester.firstWeekMonday}")

            if (discovery.needsLogin && fromServer == null) {
                trace("discovery says logged out -> 需要重新登录")
                if (!sessionManager.set(SchoolSystem.SIS, SessionState.EXPIRED, accountVersion, authenticationVersion)) return RefreshResult.Failed("会话已更新，已忽略旧响应；请刷新")
                recordAttempt(accountHash, attemptedAt, "session expired during discovery")
                return RefreshResult.SessionExpired
            }

            val pageYear = discovery.semester.academicYear
            val pageTerm = discovery.semester.termCode
            if (fromServer == null && (pageYear.isNullOrBlank() || pageTerm.isNullOrBlank()) &&
                !storedYear.isNullOrBlank() && !storedTerm.isNullOrBlank()) {
                recordAttempt(accountHash, attemptedAt, "current semester could not be verified")
                return RefreshResult.Failed("无法确认当前学期，已保留原有课表，请稍后刷新")
            }
            val year = fromServer?.xnm ?: pageYear ?: fallback.first
            val term = fromServer?.xqm ?: pageTerm ?: fallback.second
            val (matchingAnchor, matchingConfirmation) = storedConfiguration.anchorFor(year, term)
            trace("term: year=$year term=$term (fromServer=${fromServer != null}) fromWeek=$fromWeek")

            val resolvedAnchor = resolveSemesterAnchor(
                storedAnchor = matchingAnchor,
                confirmed = matchingConfirmation,
                observedAnchor = fromWeek,
                pageAnchor = discovery.semester.firstWeekMonday.takeIf {
                    (pageYear == null || pageYear == year) && (pageTerm == null || pageTerm == term)
                },
                academicYear = year,
                termCode = term
            )
            val anchor = resolvedAnchor.monday
            trace("anchor(week-1 Monday)=$anchor  [source=${resolvedAnchor.source}]")

            sessionStore.setSemesterConfiguration(
                year, term, anchor,
                confirmed = matchingConfirmation && anchor == matchingAnchor,
                verifiedAt = attemptedAt.takeIf { fromServer != null ||
                    (!pageYear.isNullOrBlank() && !pageTerm.isNullOrBlank()) }
            )

            Semester(
                academicYear = year,
                termCode = term,
                displayName = Semester.termDisplayName(term),
                firstWeekMonday = anchor
            )
        }
        trace("semester resolved: $semester")

        // This deployment's timetable JSON has an empty sjkList. Fetch the school's period table
        // once before either parsing path, so a working learned endpoint gets the same real times
        // as the built-in endpoint rather than silently using the generic fallback.
        if (!canContinue()) return RefreshResult.Failed("账号已变化，已停止旧会话的课表刷新")
        val periodTimes = runCatching {
            sisRemote.fetchPeriodTimes(
                baseUrl,
                semester.academicYear.orEmpty(),
                semester.termCode.orEmpty(),
                deadline,
                canContinue
            )
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            emptyList()
        }
        if (!canContinue()) return RefreshResult.Failed("账号已变化，已停止旧会话的课表刷新")
        trace("period times: ${periodTimes.size} slots")

        // 2) Configured / auto-learned providers run first: they encode the *real* endpoint, whereas
        //    the built-in candidates are only a best guess at the vendor's default paths.
        phase.value = RefreshPhase.READING_PROVIDER
        for (provider in knownProviders) {
            if (!canContinue()) return RefreshResult.Failed("账号已变化，已停止旧会话的课表刷新")
            if (System.currentTimeMillis() > deadline) break
            val response = providerExecutor.execute(provider, semester)
            trace("provider ${provider.name}: status=${response.status} ${response.durationMs}ms")

            if (response.error != null) continue
            if (response.status == 401 || response.status == 403) {
                if (!sessionManager.set(SchoolSystem.SIS, SessionState.EXPIRED, accountVersion, authenticationVersion)) return RefreshResult.Failed("会话已更新，已忽略旧响应；请刷新")
                recordAttempt(accountHash, attemptedAt, "session expired (provider)")
                return RefreshResult.SessionExpired
            }
            if (response.status in 300..399) {
                if (SisScheduleParser.isLoginUrl(response.finalUrl)) {
                    if (!sessionManager.set(SchoolSystem.SIS, SessionState.EXPIRED, accountVersion, authenticationVersion)) return RefreshResult.Failed("会话已更新，已忽略旧响应；请刷新")
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
                if (!sessionManager.set(SchoolSystem.SIS, SessionState.EXPIRED, accountVersion, authenticationVersion)) return RefreshResult.Failed("会话已更新，已忽略旧响应；请刷新")
                recordAttempt(accountHash, attemptedAt, "session expired (provider login page)")
                return RefreshResult.SessionExpired
            }

            val parsed = GenericTimetableParser.parse(
                body = response.body,
                provider = provider,
                semester = semester,
                accountHash = accountHash,
                source = ScheduleSource.SIS_PROVIDER,
                periodTimes = periodTimes
            )
            if (parsed is RemoteResult.Success) {
                if (!canReplaceTimetable(parsed.data, fullSemester = true)) {
                    trace("provider ${provider.name}: 0 rows -> ignoring it, continuing")
                    continue
                }
                return persist(
                    accountHash, accountVersion, parsed.data, IntegrationMode.NATIVE_API, attemptedAt, fullSemester = true
                )
            }
            trace("provider ${provider.name} rejected: ${parsed.describe()}")
        }

        // 3) Built-in ZFSoft candidates.
        phase.value = RefreshPhase.NATIVE
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
                return persist(accountHash, accountVersion, result.data, IntegrationMode.NATIVE_API, attemptedAt, fullSemester = true)
            }
            RemoteResult.SessionExpired -> {
                // Reaching here with an expired session means discovery was skipped (a learned
                // provider plus a confirmed anchor), so this is the first sign the session died.
                // Give the silent SSO renewal one shot before asking the user for anything.
                if (trySilentRenewal(baseUrl) { sessionManager.responseIsCurrent(SchoolSystem.SIS, accountVersion, authenticationVersion) } != null) {
                    val retry = runCatching {
                        sisRemote.fetchTimetable(baseUrl, semester, accountHash, deadline, periodTimes)
                    }.getOrNull()
                    val retried = retry?.result
                    if (retried is RemoteResult.Success) {
                        return persist(
                            accountHash, accountVersion, retried.data, IntegrationMode.NATIVE_API,
                            attemptedAt, fullSemester = true
                        )
                    }
                }
                if (!sessionManager.set(SchoolSystem.SIS, SessionState.EXPIRED, accountVersion, authenticationVersion)) return RefreshResult.Failed("会话已更新，已忽略旧响应；请刷新")
                recordAttempt(accountHash, attemptedAt, "session expired")
                return RefreshResult.SessionExpired
            }
            else -> trace("native failed: ${result.describe()} -> trying WebView extraction")
        }

        // 4) WebView extraction.
        phase.value = RefreshPhase.WEBVIEW
        val webResult = runCatching { sisWebView.fetchTimetable(baseUrl, semester, accountHash, canContinue) }
            .getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                trace("webview fetch threw: ${error.javaClass.simpleName}")
                RemoteResult.UnknownError(error.javaClass.simpleName)
            }

        when (webResult) {
            is RemoteResult.Success -> {
                val full = webResult.data.occurrences.all { it.source == ScheduleSource.SIS_WEBVIEW_XHR }
                return persist(
                    accountHash, accountVersion,
                    webResult.data,
                    IntegrationMode.WEBVIEW_EXTRACT,
                    attemptedAt,
                    fullSemester = full
                )
            }
            RemoteResult.SessionExpired -> {
                if (!sessionManager.set(SchoolSystem.SIS, SessionState.EXPIRED, accountVersion, authenticationVersion)) return RefreshResult.Failed("会话已更新，已忽略旧响应；请刷新")
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
        baseUrl: String,
        canContinue: () -> Boolean
    ): com.slai.campus.data.sis.SemesterProbe? {
        if (!canContinue()) return null
        val walked = runCatching { sisRemote.renewSession(baseUrl, canContinue) }.getOrDefault(false)
        if (!walked || !canContinue()) return null
        val probe = runCatching { sisRemote.probeCurrentSemester(baseUrl) }
            .getOrNull() ?: return null
        return if (canContinue() && !probe.sessionExpired && probe.term != null) {
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
        accountVersion: Long,
        parsed: SisTimetableParsed,
        mode: IntegrationMode,
        attemptedAt: Long,
        fullSemester: Boolean
    ): RefreshResult {
        phase.value = RefreshPhase.SAVING
        // Only a full, validated school timetable may explicitly clear a semester. An empty
        // generic provider array or an empty DOM week is insufficient evidence.
        if (!canReplaceTimetable(parsed, fullSemester)) {
            trace("refusing to persist 0 rows; keeping the previous cache")
            recordAttempt(accountHash, attemptedAt, "payload contained 0 rows")
            return RefreshResult.SchemaChanged("服务器返回了 0 条课程（已保留原有缓存）")
        }

        val verifiedHash = parsed.studentId?.let(com.slai.campus.core.session.AccountHasher::hash)
        val saved = sessionManager.withAccount(SchoolSystem.SIS, verifiedHash, accountVersion) { owner ->
            // Generate IDs after selecting the server-proved owner, including the very first sync.
            val rows = parsed.occurrences.map { it.toEntity(owner, attemptedAt) }
            trace("persist: ${rows.size} row(s) mode=$mode fullSemester=$fullSemester")
            if (fullSemester) scheduleDao.replaceAll(owner, rows)
            else scheduleDao.replaceDates(owner, parsed.occurrences.map { it.date }.distinct(), rows)
            syncMetaDao.upsert(
                SyncMetaEntity(
                    accountHash = owner,
                    system = SchoolSystem.SIS.key,
                    lastSuccessAt = System.currentTimeMillis(),
                    lastAttemptAt = attemptedAt,
                    parserVersion = SisConfig.PARSER_VERSION,
                    lastError = parsed.warning,
                    integrationMode = mode.name,
                    semesterFirstWeekMonday = null
                )
            )
            sessionManager.set(SchoolSystem.SIS, SessionState.AUTHENTICATED)
            parsed.studentId?.let { sessionStore.setStudentIdHint(it) }
            RefreshResult.Success(added = rows.size, total = rows.size)
        } ?: return RefreshResult.Failed("账号已变化，已忽略旧会话的课表响应")
        sessionStore.setIntegrationMode(SchoolSystem.SIS, mode)

        // Self-correcting: the payload says which term it answered for.
        parsed.reportedTerm?.let { (year, term) ->
            sessionStore.setSemesterCodes(year, term)
        }

        trace("synced: ${parsed.occurrences.size} rows via $mode (fullSemester=$fullSemester)")

        runCatching { reminderScheduler.rescheduleFromCache() }
            .onFailure { AppLog.w("reminder reschedule failed: ${it.javaClass.simpleName}") }

        return saved
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
        id = accountCacheId(accountHash),
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
