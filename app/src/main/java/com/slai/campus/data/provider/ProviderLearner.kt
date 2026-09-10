package com.slai.campus.data.provider

import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.web.CaptureRecord
import com.slai.campus.domain.provider.ApiProvider
import com.slai.campus.domain.provider.Fields
import com.slai.campus.domain.provider.ProviderPurpose
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/** One captured response that looks like a timetable, with the provider it would produce. */
data class LearnCandidate(
    val provider: ApiProvider,
    val rowCount: Int,
    val sampleKeys: List<String>,
    val score: Int,
    /** True when the payload has enough course-like markers to be adopted automatically. */
    val confident: Boolean,
    val pageUrl: String?
)

data class LearnResult(
    val candidates: List<LearnCandidate>,
    val best: LearnCandidate?,
    val inspected: Int,
    val note: String
)

/**
 * Turns a raw capture into a ready-to-use [ApiProvider].
 *
 * This is the "让 AI 去浏览器抓数据，抓出来接一下" step, done on-device: the app loads the school's own
 * page, the page issues its own request, the hook records the exact method/URL/body/response, and this
 * object recognises the timetable payload and derives the field mapping from the keys it actually saw.
 *
 * Scoring is deliberately conservative — a candidate must look like a table of course rows — because
 * silently adopting the wrong endpoint would be worse than reporting "nothing found".
 */
object ProviderLearner {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    /** Keys that strongly indicate a course row. */
    private val STRONG_KEYS = setOf("kcmc", "zcd", "xqj", "jcs", "cdmc", "kch", "jxbmc")

    /** Keys that indicate a course row in other deployments. */
    private val WEAK_KEYS = setOf(
        "xm", "xqmc", "sxbj", "jcor", "courseName", "teacher", "location", "weekday", "weeks", "periods"
    )

    fun analyze(records: List<CaptureRecord>, system: SchoolSystem): LearnResult {
        val candidates = mutableListOf<LearnCandidate>()
        var inspected = 0

        records.forEach { record ->
            if (!record.isJson) return@forEach
            inspected++
            val root = runCatching { json.parseToJsonElement(record.body) }.getOrNull() ?: return@forEach

            val arrays = findArrays(root)
            arrays.forEach { (path, array) ->
                val objects = array.filterIsInstance<JsonObject>()
                if (objects.isEmpty()) return@forEach
                val sample = objects.first()
                val keys = sample.keys.toList()
                val strong = keys.count { it in STRONG_KEYS }
                val weak = keys.count { it in WEAK_KEYS }
                // Nothing course-ish at all: not a candidate.
                if (strong == 0 && weak == 0) return@forEach

                // Two strong markers, or one strong plus two weak ones, is enough to adopt
                // automatically. Anything less is still surfaced, but disabled and labelled, so a
                // human can look at it instead of the app silently picking the wrong endpoint.
                val confident = strong >= 2 || (strong >= 1 && weak >= 2)
                val score = strong * 10 + weak + minOf(objects.size, 50)
                candidates += LearnCandidate(
                    provider = buildProvider(record, path, sample, system, objects.size),
                    rowCount = objects.size,
                    sampleKeys = keys.take(24),
                    score = score,
                    confident = confident,
                    pageUrl = record.pageUrl
                )
            }
        }

        val sorted = candidates.sortedByDescending { it.score }
        val best = sorted.firstOrNull { it.confident }
        return LearnResult(
            candidates = sorted,
            best = best,
            inspected = inspected,
            note = when {
                inspected == 0 -> "没有捕获到 JSON 请求。请在页面里实际打开一次课表，再点完成。"
                sorted.isEmpty() -> "捕获到 $inspected 个 JSON 响应，但没有一个像课表数据。"
                best != null -> "找到 ${sorted.size} 个候选，最佳候选有 ${best.rowCount} 行。"
                else -> "捕获到 ${sorted.size} 个疑似候选，但特征不足，未自动启用（见下方列表）。"
            }
        )
    }

    /** Every JSON array in the document, paired with its dot-separated path. */
    private fun findArrays(root: JsonElement, path: String = "", depth: Int = 0): List<Pair<String, JsonArray>> {
        if (depth > 4) return emptyList()
        return when (root) {
            is JsonArray -> listOf(path to root)
            is JsonObject -> root.flatMap { (key, value) ->
                val childPath = if (path.isEmpty()) key else "$path.$key"
                findArrays(value, childPath, depth + 1)
            }
            else -> emptyList()
        }
    }

    private fun buildProvider(
        record: CaptureRecord,
        rowsPath: String,
        sample: JsonObject,
        system: SchoolSystem,
        rowCount: Int
    ): ApiProvider {
        val fieldMap = deriveFieldMap(sample)
        return ApiProvider(
            id = "learned-" + shortHash(record.url + "|" + record.method),
            name = "${system.displayName}课表（自动学习）",
            system = system.key,
            purpose = ProviderPurpose.TIMETABLE.key,
            method = record.method.ifBlank { "GET" },
            url = record.url,
            body = templatize(record.requestBody),
            contentType = if (record.method.equals("POST", ignoreCase = true)) {
                "application/x-www-form-urlencoded; charset=UTF-8"
            } else {
                null
            },
            rowsPath = rowsPath,
            fieldMap = fieldMap,
            note = "从网页请求自动学习：${record.pageUrl ?: "-"}（样本 $rowCount 行）",
            enabled = true,
            learnedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * Matches the keys actually present in the payload against the known aliases, so the provider
     * works even if this deployment renamed a field.
     */
    private fun deriveFieldMap(sample: JsonObject): Map<String, String> {
        val keys = sample.keys.map { it to it.lowercase() }
        val map = mutableMapOf<String, String>()
        Fields.ALL.forEach { logical ->
            val aliases = Fields.ALIASES[logical] ?: return@forEach
            val match = keys.firstOrNull { (_, lower) -> lower in aliases.map { it.lowercase() } }
            if (match != null) map[logical] = match.first
        }
        return map
    }

    /**
     * Replaces the term values captured in the request body with placeholders, so the provider keeps
     * working next semester instead of silently returning the previous term's timetable.
     */
    private fun templatize(requestBody: String?): String? {
        if (requestBody.isNullOrBlank()) return requestBody
        val text: String = requestBody
        return text
            .replace(Regex("(^|&)xnm=\\d{4}")) { m -> m.groupValues[1] + "xnm={{xnm}}" }
            .replace(Regex("(^|&)xqm=\\d{1,3}")) { m -> m.groupValues[1] + "xqm={{xqm}}" }
    }

    private fun shortHash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(10)

    /** Field mapping for a ZFSoft-shaped payload, used as a starting point in the UI. */
    fun zfsoftFieldMap(): Map<String, String> = Fields.ZFSOFT_DEFAULTS
}
