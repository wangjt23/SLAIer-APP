package com.slai.campus.data.sis

import com.slai.campus.core.common.AppLog
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.common.IoDispatcher
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.core.network.HttpTrace
import com.slai.campus.core.network.HttpSupport.asMap
import com.slai.campus.core.network.HttpSupport.contentTypeBase
import com.slai.campus.core.network.NetworkMonitor
import com.slai.campus.core.network.SisApiClient
import com.slai.campus.core.network.SisWebClient
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.session.SessionStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.slai.campus.domain.schedule.ScheduleSource
import com.slai.campus.domain.schedule.Semester
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import com.slai.campus.data.sis.SisJson.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native read access to the academic-affairs system, reusing the WebView session.
 *
 * Every method is written so that "logged out" and "endpoint changed" are distinguishable:
 *  - `302 -> /yjsxt/xtgl/login_slogin.html`  => [RemoteResult.SessionExpired]
 *  - `200` with the login page HTML          => [RemoteResult.SessionExpired]
 *  - `200` with JSON that fails validation   => [RemoteResult.SchemaChanged]
 *  - anything else non-2xx                   => [RemoteResult.ServerError]
 */
@Singleton
class SisRemoteDataSource @Inject constructor(
    @SisApiClient private val apiClient: OkHttpClient,
    @SisWebClient private val webClient: OkHttpClient,
    private val sessionStore: SessionStore,
    private val cookieBridge: com.slai.campus.core.session.WebCookieBridge,
    private val timeProvider: TimeProvider,
    private val networkMonitor: NetworkMonitor,
    @IoDispatcher private val io: CoroutineDispatcher
) {

    /** Result of a native timetable read, including the HTTP trace for diagnostics. */
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    data class FetchOutcome(
        val result: RemoteResult<SisTimetableParsed>,
        val traces: List<HttpTrace>,
        val endpoint: String?
    )

    // ---------------------------------------------------------------------------------------
    // Session probe
    // ---------------------------------------------------------------------------------------

    suspend fun probeSession(baseUrl: String): SessionState = withContext(io) {
        if (!networkMonitor.hasNetwork) return@withContext SessionState.ERROR

        for (template in SisConfig.sessionProbeCandidates) {
            val url = SisConfig.apiUrl(baseUrl, template)
            val request = Request.Builder().url(url).get()
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            val outcome = runCatching { apiClient.newCall(request).execute() }
                .getOrElse {
                    AppLog.w("SIS probe transport failure: ${it.javaClass.simpleName}")
                    return@withContext SessionState.ERROR
                }
            outcome.use { response ->
                val location = response.header("Location")
                when {
                    response.code in 300..399 && SisScheduleParser.isLoginUrl(location) ->
                        return@withContext SessionState.EXPIRED
                    response.code in 300..399 && location != null ->
                        continue // some other redirect; try the next probe endpoint
                    response.code == 200 -> {
                        val body = runCatching { response.peekBody(8 * 1024).string() }.getOrDefault("")
                        return@withContext if (SisScheduleParser.looksLikeLoginPage(body)) {
                            SessionState.EXPIRED
                        } else {
                            SessionState.AUTHENTICATED
                        }
                    }
                    response.code == 401 || response.code == 403 -> return@withContext SessionState.EXPIRED
                    // 901 == 未登录（见 fetchTimetable 里的实测记录）。
                    response.code == 901 -> return@withContext SessionState.EXPIRED
                    else -> continue
                }
            }
        }
        SessionState.ERROR
    }

    // ---------------------------------------------------------------------------------------
    // Semester discovery
    // ---------------------------------------------------------------------------------------

    /**
     * Reads the academic year / term the timetable page defaults to, and — when the page exposes it —
     * the currently selected teaching week, which is the strongest available signal for anchoring
     * the calendar.
     */
    suspend fun discoverSemester(
        baseUrl: String,
        deadlineAtMillis: Long = Long.MAX_VALUE
    ): SemesterDiscovery = withContext(io) {
        for (template in SisConfig.timetablePageCandidates) {
            if (System.currentTimeMillis() > deadlineAtMillis) {
                AppLog.i("semester discovery deadline reached")
                break
            }
            val url = SisConfig.apiUrl(baseUrl, template)
            val request = Request.Builder().url(url).get()
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            val response = runCatching { webClient.newCall(request).execute() }.getOrNull() ?: continue
            response.use { res ->
                // 901 == 未登录。webClient 会跟随重定向，所以未登录时更常见的是最终落到登录页；
                // 两种都要认出来。
                if (res.code == 901) return@withContext SemesterDiscovery(needsLogin = true)
                if (!res.isSuccessful) continue
                val html = runCatching { res.body?.string() }.getOrNull() ?: continue
                if (SisScheduleParser.looksLikeLoginPage(html)) {
                    return@withContext SemesterDiscovery(needsLogin = true)
                }
                val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: continue
                val year = selectedOptionValue(doc, listOf("xnm", "xnmc", "xn"))
                val term = selectedOptionValue(doc, listOf("xqm", "xqmc", "xq"))
                val week = selectedOptionText(doc, listOf("xq", "xqbh", "week", "zc"))
                    ?.let { Regex("(\\d{1,2})").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                val anchor = week?.let { SemesterCalendarResolver.fromObservedWeek(it, timeProvider.today()) }
                AppLog.d("SIS semester discovery: year=$year term=$term week=$week anchor=$anchor")
                return@withContext SemesterDiscovery(
                    semester = Semester(
                        academicYear = year,
                        termCode = term,
                        displayName = buildSemesterName(year, term),
                        firstWeekMonday = anchor
                    ),
                    observedWeek = week
                )
            }
        }
        SemesterDiscovery()
    }

    private fun buildSemesterName(year: String?, term: String?): String? {
        val termName = Semester.termDisplayName(term) ?: return null
        return if (year.isNullOrBlank()) termName else "$year-$termName"
    }

    private fun selectedOptionValue(doc: org.jsoup.nodes.Document, ids: List<String>): String? =
        ids.firstNotNullOfOrNull { id ->
            doc.selectFirst("select#$id option[selected]")?.`val`()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("select#$id")?.`val`()?.takeIf { it.isNotBlank() }
        }

    private fun selectedOptionText(doc: org.jsoup.nodes.Document, ids: List<String>): String? =
        ids.firstNotNullOfOrNull { id ->
            doc.selectFirst("select#$id option[selected]")?.text()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("select#$id")?.text()?.takeIf { it.isNotBlank() }
        }

    // ---------------------------------------------------------------------------------------
    // Timetable
    // ---------------------------------------------------------------------------------------

    suspend fun fetchTimetable(
        baseUrl: String,
        semester: Semester,
        accountHash: String,
        deadlineAtMillis: Long = Long.MAX_VALUE,
        periodTimes: List<Pair<java.time.LocalTime, java.time.LocalTime>>? = null
    ): FetchOutcome = withContext(io) {
        if (!networkMonitor.hasNetwork) {
            return@withContext FetchOutcome(RemoteResult.NetworkUnavailable("设备无网络"), emptyList(), null)
        }

        val year = semester.academicYear ?: sessionStore.semesterCodes().first
        val term = semester.termCode ?: sessionStore.semesterCodes().second
        if (year.isNullOrBlank() || term.isNullOrBlank()) {
            return@withContext FetchOutcome(
                RemoteResult.SchemaChanged("无法确定学年/学期（未能从页面读取 xnm/xqm）"),
                emptyList(),
                null
            )
        }

        val traces = mutableListOf<HttpTrace>()
        var lastFailure: RemoteResult<Nothing>? = null

        for (template in SisConfig.timetableApiCandidates) {
            if (System.currentTimeMillis() > deadlineAtMillis) {
                AppLog.i("native timetable deadline reached after ${traces.size} attempt(s)")
                break
            }
            val url = SisConfig.apiUrl(baseUrl, template)
            // Confirmed against the live system: localeKey + xnm + xqm + zs.
            val body = FormBody.Builder().apply {
                SisConfig.timetableBody(year, term).forEach { (name, value) -> add(name, value) }
            }.build()
            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .build()

            val started = System.currentTimeMillis()
            val response = runCatching { apiClient.newCall(request).execute() }.getOrElse { error ->
                val trace = HttpTrace(
                    method = "POST", url = url, status = null, contentType = null, bodyPreview = null,
                    headers = emptyMap(), finalUrl = null,
                    durationMs = System.currentTimeMillis() - started,
                    failure = error.javaClass.simpleName
                )
                traces += trace
                return@withContext FetchOutcome(
                    RemoteResult.NetworkUnavailable(error.javaClass.simpleName),
                    traces,
                    url
                )
            }

            val result = response.use { res ->
                val text = runCatching { res.peekBody(4L * 1024 * 1024).string() }.getOrDefault("")
                traces += HttpTrace(
                    method = "POST",
                    url = url,
                    status = res.code,
                    contentType = res.header("Content-Type"),
                    bodyPreview = SisScheduleParser.preview(text),
                    headers = res.headers.asMap(),
                    finalUrl = res.request.url.toString(),
                    durationMs = System.currentTimeMillis() - started,
                    failure = null
                )

                when {
                    res.code in 300..399 && SisScheduleParser.isLoginUrl(res.header("Location")) ->
                        RemoteResult.SessionExpired

                    res.code in 300..399 ->
                        RemoteResult.SchemaChanged("意外的重定向：${res.header("Location")?.take(80)}")

                    res.code == 401 || res.code == 403 -> RemoteResult.SessionExpired

                    /*
                     * 901 就是"没有有效会话"。
                     *
                     * 实测（recon9，两个独立客户端互相印证）：
                     *   - 浏览器 fetch，credentials:'omit'（不带 Cookie）→ 901，响应体 0 字节；
                     *   - 同一个 URL 带有效 JSESSIONID       → 200 + 7457 字节 JSON，kbList 6 行；
                     *   - 同一个 URL 不带 Cookie 头          → 901，响应体 0 字节。
                     *
                     * 所以 901 不是 WAF 拦截、不是 UA 指纹、也不是服务端故障 —— 它就是"未登录"的
                     * 另一种说法。之前把它当 ServerError，界面上只显示"服务器错误"，用户看到的现象
                     * 却是"课表永远拉不到"，完全指不到真正的原因（该重新登录了）。
                     */
                    res.code == 901 -> RemoteResult.SessionExpired

                    !res.isSuccessful -> RemoteResult.ServerError(res.code, res.message.ifBlank { null })

                    res.contentTypeBase()?.contains("html") == true &&
                        SisScheduleParser.looksLikeLoginPage(text) -> RemoteResult.SessionExpired

                    else -> SisScheduleParser.parse(
                        body = text,
                        semester = semester,
                        source = ScheduleSource.SIS_NATIVE,
                        accountHash = accountHash,
                        periodTimes = periodTimes
                    )
                }
            }

            when (result) {
                is RemoteResult.Success -> return@withContext FetchOutcome(result, traces, url)
                RemoteResult.SessionExpired -> return@withContext FetchOutcome(result, traces, url)
                is RemoteResult.NetworkUnavailable -> return@withContext FetchOutcome(result, traces, url)
                is RemoteResult.ServerError -> lastFailure = result
                is RemoteResult.SchemaChanged -> lastFailure = result
                is RemoteResult.UnknownError -> lastFailure = result
            }
        }

        FetchOutcome(
            result = lastFailure ?: RemoteResult.SchemaChanged("没有可用的课表接口"),
            traces = traces,
            endpoint = null
        )
    }

    /**
     * 确定学年 / 学期 / 周锚点。
     *
     * 证据优先级（实测得出）：
     *  1. **服务端当前学期**：`index_cxCurrentSemester.html` 直接回答"现在是哪学期"；
     *  2. 课表页里的 `select#xnm` / `select#xqm`（本部署的首页是 JS 外壳，通常没有，实测为 null）；
     *  3. 上次成功同步时记录的值；
     *  4. 按日期推算（兜底）。
     *
     * 课表请求必须带 `xnm`/`xqm`，缺了就只能报"无法确定学年/学期" —— 这正是之前拿不到课表的原因。
     */
    suspend fun resolveSemester(
        baseUrl: String,
        discovered: SemesterDiscovery
    ): Semester = withContext(io) {
        val (storedAnchor, confirmed) = sessionStore.semesterAnchor()
        val (storedYear, storedTerm) = sessionStore.semesterCodes()

        val fromServer = runCatching { fetchCurrentSemester(baseUrl) }.getOrNull()

        val fallback = SemesterCalendarResolver.estimateTermFromDate(timeProvider.today())
        val year = fromServer?.xnm
            ?: discovered.semester.academicYear
            ?: storedYear
            ?: fallback.first
        val term = fromServer?.xqm
            ?: discovered.semester.termCode
            ?: storedTerm
            ?: fallback.second

        // 服务端直接告诉我们"现在是第几周"，这是最可靠的锚点来源。
        val fromWeek = fromServer?.week?.let {
            SemesterCalendarResolver.fromObservedWeek(it, timeProvider.today())
        }

        val anchor: LocalDate? = when {
            confirmed && storedAnchor != null -> storedAnchor
            fromWeek != null -> fromWeek
            discovered.semester.firstWeekMonday != null -> discovered.semester.firstWeekMonday
            storedAnchor != null -> storedAnchor
            else -> SemesterCalendarResolver.estimate(year, term)
        }

        if (anchor != null && (confirmed || fromWeek != null || discovered.semester.firstWeekMonday != null)) {
            sessionStore.setSemesterAnchor(anchor, confirmed = confirmed)
        }
        if (!year.isNullOrBlank() || !term.isNullOrBlank()) {
            sessionStore.setSemesterCodes(year, term)
        }

        Semester(
            academicYear = year,
            termCode = term,
            displayName = Semester.termDisplayName(term),
            firstWeekMonday = anchor
        )
    }

    /**
     * Fetches the school's period start/end times.
     *
     * `sjkList` is empty on this deployment, so without this call every course would be placed on a
     * made-up default timetable. Observed: 9 periods, 09:30-12:15 / 14:30-17:15 / 18:30-21:15.
     */
    suspend fun fetchPeriodTimes(
        baseUrl: String,
        xnm: String,
        xqm: String
    ): List<Pair<java.time.LocalTime, java.time.LocalTime>> = withContext(io) {
        for (template in SisConfig.periodTimeCandidates) {
            val url = SisConfig.apiUrl(baseUrl, template)
            val body = FormBody.Builder().apply {
                add("localeKey", "zh_CN")
                add("xnm", xnm)
                add("xqm", xqm)
                add("zs", "")
            }.build()
            val request = Request.Builder().url(url).post(body)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            val response = runCatching { apiClient.newCall(request).execute() }.getOrNull() ?: continue
            response.use { res ->
                if (!res.isSuccessful) return@use
                val text = runCatching { res.peekBody(1024L * 1024).string() }.getOrNull() ?: return@use
                val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray
                    ?: return@use
                val times = root.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val start = SisScheduleNormalizer.parseClock(obj.string("qssj")) ?: return@mapNotNull null
                    val end = SisScheduleNormalizer.parseClock(obj.string("jssj")) ?: start.plusMinutes(45)
                    start to end
                }
                if (times.isNotEmpty()) {
                    AppLog.i("period times: ${times.size} slots, first=${times.first().first}")
                    return@withContext times
                }
            }
        }
        emptyList()
    }

    /**
     * 服务端回答的当前学期，**连同这次请求的状态码**。
     *
     * 返回状态码而不只是 `ServerTerm?` 是刻意的：`null` 有两种完全不同的含义 ——
     * "会话没了"（901 / 302 到登录页）和"接口变了"（200 但不是我们认识的 JSON）。
     * 早先调用方拿到的都是 `null`，于是只能静默退回到猜测学期，把真正的原因藏了起来。
     *
     * 实测返回（`index_cxCurrentSemester.html`，46 字节）：
     * ```json
     * {"year":"2026-2027","semester":"1","week":"1"}
     * ```
     * 注意字段名是 `year` / `semester` / `week`，**不是** `xnm` / `xqm` —— 之前按 xnm/xqm 解析，
     * 结果永远取不到学期，课表请求因此发不出去。
     *
     * `year` 是 `2026-2027` 这样的区间，要取前一段；`semester` 是 1/2/3，要换成正方内部的
     * 3/12/16；`week` 顺便给出了**当前教学周**，是周锚点最可靠的来源。
     */
    suspend fun probeCurrentSemester(baseUrl: String): SemesterProbe = withContext(io) {
        var lastStatus: Int? = null
        var lastEndpoint: String? = null
        for (template in SisConfig.currentSemesterCandidates) {
            val url = SisConfig.apiUrl(baseUrl, template)
            lastEndpoint = url
            val request = Request.Builder().url(url)
                .post(FormBody.Builder().add("localeKey", "zh_CN").build())
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            val response = runCatching { apiClient.newCall(request).execute() }.getOrNull() ?: continue
            response.use { res ->
                lastStatus = res.code
                when {
                    res.code == 901 -> return@withContext SemesterProbe(null, 901, true, url)
                    res.code in 300..399 && SisScheduleParser.isLoginUrl(res.header("Location")) ->
                        return@withContext SemesterProbe(null, res.code, true, url)
                    res.code == 401 || res.code == 403 ->
                        return@withContext SemesterProbe(null, res.code, true, url)
                    !res.isSuccessful -> return@use
                    else -> {
                        val text = runCatching { res.peekBody(64 * 1024).string() }.getOrNull() ?: return@use
                        if (SisScheduleParser.looksLikeLoginPage(text)) {
                            return@withContext SemesterProbe(null, res.code, true, url)
                        }
                        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return@use
                        val term = parseServerTerm(root) ?: return@use
                        AppLog.i("current semester: xnm=${term.xnm} xqm=${term.xqm} week=${term.week}")
                        return@withContext SemesterProbe(term, res.code, false, url)
                    }
                }
            }
        }
        SemesterProbe(null, lastStatus, false, lastEndpoint)
    }

    suspend fun fetchCurrentSemester(baseUrl: String): ServerTerm? =
        probeCurrentSemester(baseUrl).term

    /**
     * 静默续期：沿着学校的 SSO 链路走一遍，看能不能**不给用户添麻烦**地换到新的会话。
     *
     * 背景：正方（ZFSoft）的 `JSESSIONID` 闲置大约半小时就失效，而 AD FS 的 `MSISAuth` 能活几小时。
     * 所以"教务系统登录过期"绝大多数时候只是**应用会话过期**，SSO 那边其实还记得你。此时把
     * `htxylogin` 重新走一遍，就能在用户完全不输入任何东西的情况下拿回一个新的 `JSESSIONID`。
     *
     * 为什么不用 `webClient` 直接跟随重定向：那会在**同一次 call** 里跨到 `sts.slai.edu.cn`，
     * 而应用层拦截器只在 call 开始时按 URL 取一次 Cookie，跨域那一段就会带上错误的 Cookie。
     * 这里改成手动逐跳处理，每一跳都按**当前 URL** 取 Cookie，并把 `Set-Cookie` 写回 WebView 的
     * Cookie 仓库 —— 全程只搬运 Cookie，不接触、不保存任何凭据。
     *
     * @return true 表示最后落在了业务页面（很可能已经拿到新会话）；false 表示 SSO 也要重新登录。
     */
    suspend fun renewSession(baseUrl: String): Boolean = withContext(io) {
        if (!networkMonitor.hasNetwork) return@withContext false

        // 入口要从**当前** baseUrl 推，不能用构建期常量：用户在设置里改了地址，续期也得跟着走。
        var url = SisConfig.entryUrlFor(baseUrl)
        val visited = mutableSetOf<String>()
        // Captured once: a computed getter would hand back a fresh 15 s window on every read and the
        // loop could never time out.
        val deadline = System.currentTimeMillis() + RENEW_BUDGET_MS

        repeat(MAX_SSO_HOPS) {
            if (System.currentTimeMillis() > deadline) {
                AppLog.i("silent renewal: deadline reached")
                return@withContext false
            }
            if (!visited.add(url)) {
                AppLog.i("silent renewal: redirect loop at ${SisScheduleParser.preview(url)}")
                return@withContext false
            }

            val cookie = cookieBridge.cookieHeaderBlocking(url)
            val request = Request.Builder().url(url).get()
                .header("Accept", "text/html,application/xhtml+xml")
                .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
                .build()

            val response = runCatching { apiClient.newCall(request).execute() }.getOrNull()
                ?: return@withContext false

            response.use { res ->
                // Cookies handed out mid-flight (a fresh JSESSIONID, or an AD FS refresh) belong in
                // the WebView store, otherwise the next hop cannot see them.
                cookieBridge.saveSetCookieBlocking(url, res.headers("Set-Cookie"))

                val location = res.header("Location")
                if (location.isNullOrBlank()) {
                    val body = runCatching { res.peekBody(64 * 1024).string() }.getOrDefault("")
                    // Must end on the school's own business host: a 200 served by the AD FS host is
                    // its login page, not a restored session. (The caller re-probes anyway, but a
                    // wrong "success" here would make the logs lie.)
                    val baseHost = runCatching { baseUrl.toHttpUrlOrNull()?.host }.getOrNull()
                    val host = runCatching { url.toHttpUrlOrNull()?.host }.getOrNull()
                    val landed = res.isSuccessful &&
                        host != null && host == baseHost &&
                        !SisScheduleParser.looksLikeLoginPage(body)
                    AppLog.i("silent renewal: ended at ${SisScheduleParser.preview(url)} ok=$landed")
                    return@withContext landed
                }

                val next = absolutize(url, location) ?: return@withContext false
                // Landing back on the explicit login form means AD FS itself wants credentials.
                if (SisScheduleParser.isLoginUrl(next)) {
                    AppLog.i("silent renewal: SSO wants credentials again")
                    return@withContext false
                }
                url = next
            }
        }
        AppLog.i("silent renewal: hop limit reached")
        false
    }

    private fun absolutize(base: String, location: String): String? =
        runCatching { base.toHttpUrlOrNull()?.resolve(location)?.toString() }.getOrNull()

    /** 把服务端的 `{year, semester, week}` 翻译成正方的 `xnm` / `xqm`。 */

    /** Monday of the current week, used to window the sync. */
    fun currentWeekMonday(): LocalDate =
        timeProvider.today().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

    companion object {
        val system: SchoolSystem = SchoolSystem.SIS

        /** SSO 链路的跳数上限（AD FS 一次来回大约 4~6 跳）。 */
        private const val MAX_SSO_HOPS = 12

        /** 静默续期的总预算，超了就放弃并让用户手动登录。 */
        private const val RENEW_BUDGET_MS = 15_000L
    }
}

/** What the timetable page told us about the current term. */
data class SemesterDiscovery(
    val semester: Semester = Semester(),
    val observedWeek: Int? = null,
    val needsLogin: Boolean = false
)

internal fun parseServerTerm(root: kotlinx.serialization.json.JsonElement): ServerTerm? {
    val yearRaw = findString(root, "year", "xnm", "XNM", "xnmc")
    val semesterRaw = findString(root, "semester", "xqm", "XQM")

    val xnm = yearRaw
        ?.substringBefore('-')          // 2026-2027 -> 2026
        ?.trim()
        ?.takeIf { it.matches(Regex("\\d{4}")) }
        ?: return null

    val xqm = when (semesterRaw?.trim()) {
        "1" -> SisConfig.Terms.FIRST
        "2" -> SisConfig.Terms.SECOND
        "3" -> SisConfig.Terms.THIRD
        // 已经是内部编码（3/12/16）时直接使用
        SisConfig.Terms.FIRST, SisConfig.Terms.SECOND, SisConfig.Terms.THIRD -> semesterRaw.trim()
        else -> return null
    }

    val week = findString(root, "week", "zc", "zcbh")?.let {
        Regex("(\\d{1,2})").find(it)?.groupValues?.get(1)?.toIntOrNull()
    }?.takeIf { it in 1..60 }

    return ServerTerm(xnm, xqm, week)
}

/** Depth-first search for the first non-blank value among [keys]. */
private fun findString(
    element: kotlinx.serialization.json.JsonElement,
    vararg keys: String
): String? {
    val queue = ArrayDeque<kotlinx.serialization.json.JsonElement>()
    queue.addLast(element)
    while (queue.isNotEmpty()) {
        when (val current = queue.removeFirst()) {
            is JsonObject -> {
                for (key in keys) {
                    current.string(key)?.let { return it }
                }
                current.values.forEach { queue.addLast(it) }
            }
            is JsonArray -> current.forEach { queue.addLast(it) }
            else -> Unit
        }
    }
    return null
}

data class ServerTerm(
    val xnm: String,
    val xqm: String,
    /** 当前教学周（1 起）。 */
    val week: Int?
)

/**
 * 一次"当前学期"探测的完整结果。
 *
 * [status] 是原始 HTTP 状态码，[sessionExpired] 说明这个 `null` 到底是"没登录"还是"接口变了"。
 */
data class SemesterProbe(
    val term: ServerTerm?,
    val status: Int?,
    val sessionExpired: Boolean,
    val endpoint: String?
)
