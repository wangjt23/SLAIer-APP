package com.slai.campus.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slai.campus.core.common.Redactor
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.common.describe
import com.slai.campus.core.session.AccountHasher
import com.slai.campus.core.session.SessionManager
import com.slai.campus.core.session.SessionStore
import com.slai.campus.core.web.AppUrlProvider
import com.slai.campus.data.sis.SisConfig
import com.slai.campus.data.sis.SisRemoteDataSource
import com.slai.campus.data.sis.SisWebViewDataSource
import com.slai.campus.data.stu.StuRemoteDataSource
import com.slai.campus.data.stu.StuAttendanceDataSource
import com.slai.campus.data.stu.StuPunchDataSource
import com.slai.campus.data.stu.StuWebViewDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * The developer/diagnostic console the design plan asks for in §8 and §29.
 *
 * Purpose: let the user (on their own phone, with their own login) prove out the native read path
 * that could not be verified from a build machine. Every line of output is passed through
 * [Redactor], so a screenshot or a copy/paste into a chat cannot leak a session cookie, a student
 * number or a token.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val sisRemote: SisRemoteDataSource,
    private val sisWebView: SisWebViewDataSource,
    private val stuRemote: StuRemoteDataSource,
    private val stuWebView: StuWebViewDataSource,
    private val stuAttendance: StuAttendanceDataSource,
    private val stuPunch: StuPunchDataSource,
    private val sessionManager: SessionManager,
    private val sessionStore: SessionStore,
    private val urlProvider: AppUrlProvider,
    private val cookies: com.slai.campus.core.session.WebCookieBridge,
    private val scheduleRepository: com.slai.campus.domain.schedule.ScheduleRepository,
    private val scheduleDao: com.slai.campus.core.database.ScheduleDao,
    private val timeProvider: com.slai.campus.core.common.TimeProvider
) : ViewModel() {

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun clear() {
        _output.value = ""
    }

    private fun log(line: String) {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _output.value = _output.value + "[$stamp] " + Redactor.redact(line) + "\n"
    }

    private fun runProbe(block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: Exception) {
                log("!! ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                _busy.value = false
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    fun probeSessions() = runProbe {
        log("=== session probe ===")
        val snapshot = sessionManager.probeAll()
        log("SIS: ${snapshot.sis}")
        log("STU: ${snapshot.stu}")
        log("accountHash: ${AccountHasher.display(snapshot.accountHash)}")
    }

    fun testSisNative() = runProbe {
        log("=== SIS native timetable request ===")
        val urls = urlProvider.current()
        val hash = sessionManager.requireAccountHash()

        // 0) Cookie 一定先看：到目前为止"课表拉不到"的唯一根因就是这里为空。
        log("cookie @ ${urls.sisBase}: ${cookies.cookieSummary(urls.sisBase)}")
        log("cookie @ 课表接口: ${cookies.cookieSummary("${urls.sisBase}/kbcx/xskbcx_cxXsKb.html")}")

        // 1) 学年/学期：服务端优先，并且把状态码一起打出来
        val probe = runCatching { sisRemote.probeCurrentSemester(urls.sisBase) }.getOrNull()
        log(
            "currentSemester endpoint: status=${probe?.status ?: "-"} " +
                "sessionExpired=${probe?.sessionExpired ?: "-"} " +
                (probe?.term?.let { "xnm=${it.xnm} xqm=${it.xqm} week=${it.week}" } ?: "未取到学期")
        )
        if (probe?.sessionExpired == true) {
            log("-> 901 / 跳登录页 = 未登录。课表请求必然失败，请先登录教务系统。")
            return@runProbe
        }

        val discovery = sisRemote.discoverSemester(urls.sisBase)
        log(
            "semester page: year=${discovery.semester.academicYear} term=${discovery.semester.termCode} " +
                "observedWeek=${discovery.observedWeek} needsLogin=${discovery.needsLogin}"
        )
        if (discovery.needsLogin) {
            log("-> 未登录：请先打开教务系统并完成登录")
            return@runProbe
        }

        val semester = sisRemote.resolveSemester(urls.sisBase, discovery)
        log("resolved term: xnm=${semester.academicYear} xqm=${semester.termCode} (${semester.displayName})")
        log("resolved anchor (week-1 Monday): ${semester.firstWeekMonday}")
        if (semester.academicYear.isNullOrBlank() || semester.termCode.isNullOrBlank()) {
            log("-> 仍然无法确定学年/学期，课表请求无法发出")
            return@runProbe
        }

        // 2) 节次时间
        val periodTimes = runCatching {
            sisRemote.fetchPeriodTimes(urls.sisBase, semester.academicYear.orEmpty(), semester.termCode.orEmpty())
        }.getOrDefault(emptyList())
        log("period times: ${periodTimes.size} 节" + periodTimes.firstOrNull()?.let { " (第1节 ${it.first})" }.orEmpty())

        // 3) 课表
        val outcome = sisRemote.fetchTimetable(urls.sisBase, semester, hash, periodTimes = periodTimes)
        outcome.traces.forEach { trace ->
            log("--- ${trace.method} ${Redactor.redactUrl(trace.url)}")
            log("    status=${trace.status} ct=${trace.contentType} ${trace.durationMs}ms")
            trace.bodyPreview?.let { log("    body: $it") }
        }
        when (val result = outcome.result) {
            is RemoteResult.Success -> {
                log("-> SUCCESS: ${result.data.occurrences.size} occurrences from ${result.data.rawItemCount} rows")
                log("   skipped=${result.data.skippedRows} warning=${result.data.warning}")
                result.data.occurrences.take(6).forEach {
                    log("   ${it.date} ${it.timeRange} ${it.courseName} @${it.location ?: "-"} (week ${it.weekIndex})")
                }
            }
            else -> log("-> ${result.describe()}")
        }
    }

    /**
     * Runs the **real** refresh — the same call the home screen's 刷新 button makes — and then reads
     * the database back.
     *
     * This button exists because [testSisNative] exercises the data sources directly, which turned
     * out to be a different code path from `ScheduleRepository.refresh`. It could report a flawless
     * "84 occurrences from 6 rows" while the actual refresh was failing a step earlier, so every
     * previous investigation was chasing the wrong half of the pipeline.
     */
    fun testRealRefresh() = runProbe {
        log("=== 真实刷新（与首页刷新按钮完全相同的链路）===")
        val urls = urlProvider.current()
        log("cookie @ ${urls.sisBase}: ${cookies.cookieSummary(urls.sisBase)}")
        log("session: sis=${sessionManager.current().sis}")

        val result = try {
            scheduleRepository.refresh(com.slai.campus.domain.schedule.RefreshReason.MANUAL)
        } catch (e: Exception) {
            log("!! 刷新抛异常 ${e.javaClass.simpleName}: ${e.message}")
            return@runProbe
        }
        log("-> 刷新结果: $result")

        val lines = scheduleRepository.observeRefreshTrace().first()
        log("--- 刷新过程（${lines.size} 步）---")
        lines.forEach { log("   $it") }

        // 直接查数据库：分清"没抓到"和"没写进去"
        val hash = sessionManager.requireAccountHash()
        val today = timeProvider.today()
        val monday = today.with(
            java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)
        )
        val rows = scheduleDao.rangeOnce(hash, monday, monday.plusDays(6))
        log("--- 数据库 ---")
        log("本周 ${monday} ~ ${monday.plusDays(6)}: ${rows.size} 行；账号总计 ${scheduleDao.count(hash)} 行")
        val earliest = scheduleDao.earliestDate(hash)
        val latest = scheduleDao.latestDate(hash)
        log("缓存日期范围: $earliest ~ $latest")
        rows.take(8).forEach { log("   ${it.date} ${it.startTime} ${it.courseName}") }
        if (rows.isEmpty() && scheduleDao.count(hash) > 0) {
            log("   ↑ 库里有数据但本周没有 → 多半是周锚点不对")
        } else if (scheduleDao.count(hash) == 0) {
            log("   ↑ 库里一条都没有 → 刷新没走到写入")
        }
        log("anchor=${sessionStore.semesterAnchor()} codes=${sessionStore.semesterCodes()}")
    }

    fun testSisWebView() = runProbe {
        log("=== SIS WebView extraction ===")
        val urls = urlProvider.current()
        val hash = sessionManager.requireAccountHash()
        val discovery = sisRemote.discoverSemester(urls.sisBase)
        val semester = sisRemote.resolveSemester(urls.sisBase, discovery)
        when (val result = sisWebView.fetchTimetable(urls.sisBase, semester, hash)) {
            is RemoteResult.Success -> {
                log("-> SUCCESS: ${result.data.occurrences.size} occurrences")
                log("   warning=${result.data.warning}")
                result.data.occurrences.take(5).forEach {
                    log("   ${it.date} ${it.timeRange} ${it.courseName} [${it.source}]")
                }
            }
            else -> log("-> ${result.describe()}")
        }
    }

    fun testStuNative() = runProbe {
        log("=== STU native check-in probe ===")
        val urls = urlProvider.current()
        val outcome = stuRemote.fetchCheckIn(urls.stuBase)
        log(outcome.trace.trim())
        log("-> endpoint=${outcome.endpoint} result=${outcome.result.describe()}")
        (outcome.result as? RemoteResult.Success)?.let { log("   answer=${it.data}") }
    }

    fun testStuWebView() = runProbe {
        log("=== STU WebView extraction ===")
        val urls = urlProvider.current()
        val outcome = stuWebView.fetchCheckIn(urls.stuBase)
        log(outcome.note.trim())
        log("-> url=${outcome.url} result=${outcome.result.describe()}")
        (outcome.result as? RemoteResult.Success)?.let { log("   answer=${it.data}") }
    }

    fun testAttendance() = runProbe {
        log("=== STU 考勤接口 ===")
        val urls = urlProvider.current()
        val month = stuAttendance.currentMonth()
        val outcome = stuAttendance.fetchMonth(urls.stuBase, month)
        log(outcome.trace.trim())
        when (val result = outcome.result) {
            is RemoteResult.Success -> {
                log("-> SUCCESS: ${result.data.records.size} 天，${result.data.weeks.size} 周")
                log("   stats=${result.data.stats}")
                result.data.records.take(5).forEach {
                    log("   ${it.date} ${it.swipeRange} 在馆=${it.durationText ?: "-"} 合格=${it.qualified}")
                }
            }
            else -> log("-> ${result.describe()}")
        }
    }

    fun testPunches() = runProbe {
        log("=== STU 闸机刷卡记录（listData）===")
        val urls = urlProvider.current()
        val today = java.time.LocalDate.now()
        val outcome = stuPunch.fetchPunches(urls.stuBase, today.minusDays(6), today)
        log(outcome.trace.trim())
        when (val result = outcome.result) {
            is RemoteResult.Success -> {
                log("-> SUCCESS: ${result.data.size} 条刷卡记录")
                val daily = com.slai.campus.domain.attendance.PunchPairing.of(
                    today,
                    result.data.filter { it.date == today },
                    java.time.LocalDateTime.now()
                )
                log("   今日 ${daily.punches.size} 次刷卡，配对 ${daily.sessions.size} 段，" +
                    "累计 ${daily.textAt(java.time.LocalDateTime.now())}" +
                    if (daily.currentlyInsideAt(java.time.LocalDateTime.now())) "（在馆中）" else "")
                daily.sessions.forEach { s ->
                    log("     ${s.textAt(java.time.LocalDateTime.now())} = " +
                        com.slai.campus.domain.attendance.AttendanceRecord.formatMinutes(
                            s.minutesAt(java.time.LocalDateTime.now())
                        ))
                }
            }
            else -> log("-> ${result.describe()}")
        }
    }

    fun dumpConfig() = runProbe {
        log("=== configuration ===")
        val urls = urlProvider.current()
        log("SIS base: ${urls.sisBase}")
        log("SIS entry: ${urls.sisEntry}")
        log("STU base: ${urls.stuBase}")
        log("STU entry: ${urls.stuEntry}")
        log("SIS cookie: ${cookies.cookieSummary(urls.sisBase)}")
        log("STU cookie: ${cookies.cookieSummary(urls.stuBase)}")
        log("session state: sis=${sessionStore.current().sis} stu=${sessionStore.current().stu}")
        log("SIS candidates:")
        SisConfig.timetableApiCandidates.forEach { log("  ${SisConfig.apiUrl(urls.sisBase, it)}") }
        log("semester codes: ${sessionStore.semesterCodes()}")
        log("anchor: ${sessionStore.semesterAnchor()}")
    }

    fun exportReport(): String {
        val header = buildString {
            appendLine("# SLAI Campus 诊断报告")
            appendLine("# 生成时间：${LocalDateTime.now()}")
            appendLine("# 说明：所有 Cookie / Token / 学号 / 密码已脱敏")
            appendLine()
        }
        return Redactor.redact(header + _output.value)
    }

    fun currentSystem(): SchoolSystem = SchoolSystem.SIS
}
