package com.slai.campus.feature.provider

import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.common.describe
import com.slai.campus.core.session.SessionStore
import com.slai.campus.core.web.CaptureRecord
import com.slai.campus.core.web.CaptureSession
import com.slai.campus.core.web.InterceptedRequest
import com.slai.campus.data.provider.CaptureBus
import com.slai.campus.data.provider.CaptureReport
import com.slai.campus.data.provider.GenericTimetableParser
import com.slai.campus.data.provider.ProviderExecutor
import com.slai.campus.data.provider.ProviderLearner
import com.slai.campus.data.provider.ProviderStore
import com.slai.campus.data.sis.SisConfig
import com.slai.campus.data.sis.SisRemoteDataSource
import com.slai.campus.domain.provider.ApiProvider
import com.slai.campus.domain.provider.ProviderPurpose
import com.slai.campus.domain.schedule.RefreshReason
import com.slai.campus.domain.schedule.ScheduleRepository
import com.slai.campus.domain.schedule.ScheduleSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context

data class ProviderUiState(
    val providers: List<ApiProvider> = emptyList(),
    val editorText: String = "",
    val editorError: String? = null,
    val busy: Boolean = false,
    val log: String = "",
    val captureStartUrl: String = SisConfig.defaultEntryUrl,
    val captureSummary: String = "",
    val hasCapture: Boolean = false
)

/**
 * Backs the API-provider screen: paste/edit a provider, test it against the live session, run the
 * capture tool, and see exactly what came back.
 *
 * This is the screen that replaces "guess the endpoint in Kotlin" with "describe the endpoint as
 * data and prove it works".
 */
@HiltViewModel
class ProviderViewModel @Inject constructor(
    private val providerStore: ProviderStore,
    private val providerExecutor: ProviderExecutor,
    private val captureBus: CaptureBus,
    private val sisRemote: SisRemoteDataSource,
    private val sessionStore: SessionStore,
    private val scheduleRepository: ScheduleRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val editor = MutableStateFlow("")
    private val error = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val log = MutableStateFlow("")
    private val captureStartUrl = MutableStateFlow(SisConfig.defaultEntryUrl)
    private val lastCapture = MutableStateFlow<List<CaptureRecord>>(emptyList())
    private val lastRequests = MutableStateFlow<List<InterceptedRequest>>(emptyList())

    val providers: StateFlow<List<ApiProvider>> = providerStore.providers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private data class Core(
        val providers: List<ApiProvider>,
        val editorText: String,
        val editorError: String?,
        val busy: Boolean,
        val log: String
    )

    // combine() has typed overloads only up to 5 flows; fold the sixth in separately.
    private val core = kotlinx.coroutines.flow.combine(
        providers, editor, error, busy, log
    ) { list, text, err, isBusy, logText -> Core(list, text, err, isBusy, logText) }

    val state: StateFlow<ProviderUiState> = kotlinx.coroutines.flow.combine(
        core, captureStartUrl, lastCapture
    ) { c, startUrl, capture ->
        ProviderUiState(
            c.providers, c.editorText, c.editorError, c.busy, c.log, startUrl,
            captureSummary = CaptureReport.summary(capture, lastRequests.value),
            hasCapture = capture.isNotEmpty() || lastRequests.value.isNotEmpty()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProviderUiState())

    fun setCaptureStartUrl(url: String) {
        captureStartUrl.value = url.trim()
    }

    init {
        viewModelScope.launch {
            // 抓包要从**当前**地址的登录入口开始：用户在设置里改过地址时，构建期常量就不准了。
            captureStartUrl.value = SisConfig.entryUrlOrDefault(sessionStore.baseUrl(SchoolSystem.SIS))
            captureBus.pending.collect { session ->
                if (session != null) {
                    captureBus.clear()
                    onCaptureFinished(session)
                }
            }
        }
    }

    fun onEditorChanged(text: String) {
        editor.value = text
        error.value = null
    }

    fun clearLog() {
        log.value = ""
    }

    private fun append(line: String) {
        log.value = log.value + line + "\n"
    }

    // ---- CRUD ------------------------------------------------------------------------------

    fun saveFromEditor() {
        val parsed = ApiProvider.fromJson(editor.value)
        parsed.onFailure {
            error.value = "JSON 解析失败：${it.message?.take(120)}"
            return
        }
        val provider = parsed.getOrThrow()
        val problems = provider.validate()
        if (problems.isNotEmpty()) {
            error.value = problems.joinToString("；")
            return
        }
        viewModelScope.launch {
            providerStore.upsert(provider)
            editor.value = ""
            append("已保存 provider：${provider.name}")
            toast("已保存")
        }
    }

    fun loadIntoEditor(provider: ApiProvider) {
        editor.value = ApiProvider.toJson(provider)
        error.value = null
    }

    fun newTemplate() {
        editor.value = ApiProvider.toJson(
            ApiProvider(
                id = "manual-${System.currentTimeMillis()}",
                name = "教务课表（手填）",
                system = SchoolSystem.SIS.key,
                purpose = ProviderPurpose.TIMETABLE.key,
                method = "POST",
                url = "https://sis.slai.edu.cn/yjsxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=index",
                body = "localeKey=zh_CN&xnm={{xnm}}&xqm={{xqm}}&zs=",
                contentType = "application/x-www-form-urlencoded; charset=UTF-8",
                rowsPath = "kbList",
                fieldMap = ProviderLearner.zfsoftFieldMap(),
                note = "已按 2026-09-09 实机抓包结果预填，可直接测试"
            )
        )
    }

    fun delete(id: String) {
        viewModelScope.launch { providerStore.delete(id) }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { providerStore.setEnabled(id, enabled) }
    }

    // ---- testing ---------------------------------------------------------------------------

    fun test(provider: ApiProvider) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                append("=== 测试 ${provider.name} ===")
                val semester = resolveSemester()
                val response = providerExecutor.execute(provider, semester)
                append(response.render().trim())
                if (!response.isSuccess) {
                    append("→ 请求失败，provider 未生效")
                    return@launch
                }
                when (
                    val parsed = GenericTimetableParser.parse(
                        body = response.body,
                        provider = provider,
                        semester = semester,
                        accountHash = "test",
                        source = ScheduleSource.SIS_PROVIDER
                    )
                ) {
                    is com.slai.campus.core.common.RemoteResult.Success -> {
                        append("→ 解析成功：${parsed.data.occurrences.size} 条课程（原始 ${parsed.data.rawItemCount} 行）")
                        parsed.data.occurrences.take(5).forEach {
                            append("   ${it.date} ${it.timeRange} ${it.courseName} @${it.location ?: "-"}")
                        }
                    }
                    else -> append("→ 解析失败：${parsed.describe()}")
                }
            } finally {
                busy.value = false
            }
        }
    }

    /** Runs every enabled provider and reports which one actually works. */
    fun testAll() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                val list = providers.value.filter { it.enabled }
                if (list.isEmpty()) {
                    append("没有启用的 provider")
                    return@launch
                }
                val semester = resolveSemester()
                list.forEach { provider ->
                    append("--- ${provider.name}")
                    val response = providerExecutor.execute(provider, semester)
                    append("    ${response.status ?: "-"} ${response.durationMs}ms ${response.contentType ?: ""}")
                    if (response.isSuccess) {
                        val parsed = GenericTimetableParser.parse(
                            response.body, provider, semester, "test", ScheduleSource.SIS_PROVIDER
                        )
                        append(
                            when (parsed) {
                                is com.slai.campus.core.common.RemoteResult.Success ->
                                    "    → OK，${parsed.data.occurrences.size} 条课程"
                                else -> "    → ${parsed.describe()}"
                            }
                        )
                    } else {
                        append("    → ${response.error ?: "HTTP ${response.status}"}")
                    }
                }
            } finally {
                busy.value = false
            }
        }
    }

    /** Applies the first working provider by triggering a full refresh. */
    fun refreshNow() {
        viewModelScope.launch {
            append("触发一次完整刷新…")
            val result = scheduleRepository.refresh(RefreshReason.MANUAL)
            append("刷新结果：$result")
        }
    }

    // ---- learning --------------------------------------------------------------------------

    /** Called when the capture WebView finishes: analyse, save the best candidate, report. */
    fun onCaptureFinished(session: CaptureSession) {
        viewModelScope.launch {
            busy.value = true
            try {
                lastCapture.value = session.records
                lastRequests.value = session.requests

                append("=== 抓包分析 ===")
                append(CaptureReport.summary(session.records, session.requests))

                // Path A: a response body we actually saw looks like a timetable.
                val result = ProviderLearner.analyze(session.records, SchoolSystem.SIS)
                append(result.note)
                result.candidates.take(6).forEach { candidate ->
                    append(
                        "候选：${candidate.provider.method} ${candidate.provider.url}\n" +
                            "     rowsPath=${candidate.provider.rowsPath} 行数=${candidate.rowCount} " +
                            "score=${candidate.score} 置信=${if (candidate.confident) "是" else "否"}"
                    )
                }
                result.best?.let { best ->
                    providerStore.upsert(best.provider)
                    append("→ 已保存并启用：${best.provider.name}")
                    toast("已学习并启用课表接口（${best.rowCount} 行）")
                    scheduleRepository.refresh(RefreshReason.MANUAL)
                    return@launch
                }

                // Path B: the request bodies were invisible (iframe), so probe the URLs the page
                // actually called and see which one answers with a timetable.
                append("")
                append("=== 逐个探测捕获到的接口 ===")
                val found = probeUrlCandidates(session)
                if (found != null) {
                    providerStore.upsert(found)
                    append("→ 已找到并启用：${found.name}")
                    toast("已找到课表接口（${found.rowsPath}）")
                    scheduleRepository.refresh(RefreshReason.MANUAL)
                    return@launch
                }

                append("→ 没有找到可用的课表接口")
                result.candidates.firstOrNull()?.let { candidate ->
                    providerStore.upsert(
                        candidate.provider.copy(
                            enabled = false,
                            name = "${candidate.provider.name}（待确认）",
                            note = (candidate.provider.note ?: "") + " · 特征不足，请先点「测试」"
                        )
                    )
                    append("已保存一个未启用配置，可点「测试」手工确认")
                }
                toast("没有自动识别到课表接口，请点「复制抓包报告」发我")
            } finally {
                busy.value = false
                append("")
                append("=== 抓包报告（点上方「复制抓包报告」按钮）===")
                append(CaptureReport.build(session.records, session.requests))
            }
        }
    }

    /**
     * Replays every plausible non-static request the page made and keeps the first one whose response
     * parses into a timetable.
     *
     * This is the answer to iframe-hosted modules: even when the response body was never visible to
     * the hook, the URL was, and replaying it with the session cookies reveals whether it is the one.
     */
    private suspend fun probeUrlCandidates(session: CaptureSession): ApiProvider? {
        val semester = resolveSemester()
        val xhrByUrl = session.records.associateBy { it.url }

        val candidates = session.requests
            .filter { it.url.startsWith("https://") }
            .filterNot { request ->
                val lower = request.url.lowercase()
                STATIC_EXTENSIONS.any { lower.contains(it) } ||
                    EXCLUDED_PATHS.any { lower.contains(it) }
            }
            .distinctBy { it.url }
            .sortedByDescending { scoreUrl(it.url) }
            .take(15)

        if (candidates.isEmpty()) {
            append("（没有可探测的接口请求）")
            return null
        }

        candidates.forEach { request ->
            val known = xhrByUrl[request.url]
            val recordedMethod = (known?.method ?: request.method).uppercase()
            val recordedBody = known?.requestBody?.takeIf { it.isNotBlank() }

            // Try the method the page used first, then the other one: an iframe request gives us the
            // URL but not the body, and a POST endpoint replayed as GET would just redirect.
            val attempts = buildList {
                add(recordedMethod to recordedBody)
                val other = if (recordedMethod == "POST") "GET" else "POST"
                if (other == "POST") {
                    // The term parameters are the one thing a timetable POST always needs.
                    add("POST" to (recordedBody ?: "xnm={{xnm}}&xqm={{xqm}}&kzlx=ck"))
                } else {
                    add("GET" to null)
                }
            }.distinct()

            for ((method, body) in attempts) {
                val provider = ApiProvider(
                    id = "probe-" + (request.url + method).hashCode().toString(16),
                    name = "教务接口（自动探测）",
                    system = SchoolSystem.SIS.key,
                    purpose = ProviderPurpose.TIMETABLE.key,
                    method = method,
                    url = request.url,
                    body = body,
                    contentType = if (method == "POST") {
                        "application/x-www-form-urlencoded; charset=UTF-8"
                    } else {
                        null
                    },
                    rowsPath = "",
                    note = "自动探测于抓包会话"
                )

                val response = providerExecutor.execute(provider, semester)
                if (!response.isSuccess) {
                    append("  $method ${response.status ?: "-"} ${request.url.substringAfter("slai.edu.cn")}")
                    continue
                }

                val parsed = GenericTimetableParser.parse(
                    response.body, provider, semester, "probe", ScheduleSource.SIS_PROVIDER
                )
                if (parsed is com.slai.campus.core.common.RemoteResult.Success &&
                    parsed.data.occurrences.isNotEmpty()
                ) {
                    append("  ✓ $method ${response.status} ${request.url} → ${parsed.data.occurrences.size} 条课程")
                    return provider.copy(
                        name = "教务课表（自动探测）",
                        enabled = true,
                        learnedAt = System.currentTimeMillis(),
                        createdAt = System.currentTimeMillis(),
                        note = "自动探测：$method ${request.url}"
                    )
                }
                append("  $method ${response.status} ${request.url.substringAfter("slai.edu.cn")} → ${parsed.describe()}")
            }
        }
        return null
    }

    private fun scoreUrl(url: String): Int {
        val lower = url.lowercase()
        var score = 0
        HINT_WORDS.forEach { word -> if (lower.contains(word)) score += 10 }
        if (lower.contains("cx")) score += 2
        return score
    }

    /** Copies the redacted capture report to the clipboard. */
    fun copyReport() {
        val records = lastCapture.value
        if (records.isEmpty()) {
            toast("还没有抓包数据，请先点「从网页抓取」")
            return
        }
        val report = CaptureReport.build(records, lastRequests.value)
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("campus-capture-report", report))
        append("已复制抓包报告（${records.size} 个请求）")
        toast("报告已复制，直接发我即可")
    }

    /** Exports every captured request as a redacted text report, for offline analysis. */
    fun dumpCapture(records: List<CaptureRecord>) {
        viewModelScope.launch {
            append("=== 抓包明细（已脱敏）===")
            records.forEachIndexed { index, record ->
                append("[$index] ${record.method} ${com.slai.campus.core.common.Redactor.redactUrl(record.url)}")
                append("    status=${record.status} page=${com.slai.campus.core.common.Redactor.redactUrl(record.pageUrl)}")
                record.requestBody?.takeIf { it.isNotBlank() }?.let {
                    append("    req=${com.slai.campus.core.common.Redactor.redact(it.take(300))}")
                }
                append("    body=${com.slai.campus.core.common.Redactor.preview(record.body, 200)}")
            }
        }
    }

    private companion object {
        val STATIC_EXTENSIONS = listOf(
            ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".woff", ".woff2",
            ".ttf", ".ico", ".map", ".mp4", ".webp"
        )
        val EXCLUDED_PATHS = listOf(
            "adfs", "login", "logout", "captcha", "yzm", "checkcode", "captcha", "oauth2"
        )
        val HINT_WORDS = listOf(
            "kb", "kbcx", "course", "schedule", "paike", "pkgl", "xskbcx", "timetable", "kecheng"
        )
    }

    private suspend fun resolveSemester(): com.slai.campus.domain.schedule.Semester {
        val baseUrl = com.slai.campus.data.sis.SisConfig
            .baseUrlOrDefault(sessionStore.baseUrl(SchoolSystem.SIS))
        val discovery = runCatching { sisRemote.discoverSemester(baseUrl) }
            .getOrElse { com.slai.campus.data.sis.SemesterDiscovery() }
        return runCatching { sisRemote.resolveSemester(baseUrl, discovery) }
            .getOrElse { com.slai.campus.domain.schedule.Semester() }
    }

    private fun toast(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
    }
}
