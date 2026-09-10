package com.slai.campus.core.common

import java.util.Locale

/**
 * Scrubs anything that could be replayed against the school systems.
 *
 * Two audiences:
 *  - **Release logs / diagnostics UI**: a user may screenshot or export a report, so cookies, tokens,
 *    student numbers and passwords must be replaced before they are ever rendered.
 *  - **Crash reports**: exception messages can embed a request URL with a `code` query parameter.
 *
 * The redactor is deliberately conservative: it errs on the side of removing too much.
 */
object Redactor {

    private val COOKIE_PAIR = Regex("(?i)\\b([A-Za-z0-9_\\-]{2,40})=([^;\\s,]{4,})")

    private val SECRET_KEYS = listOf(
        "password", "passwd", "pwd", "mm", "secret", "token", "access_token", "refresh_token",
        "id_token", "authorization", "auth", "cookie", "set-cookie", "jsessionid", "sessionid",
        "session_id", "jeesite.session.id", "code", "ticket", "assertion", "samlresponse",
        "samlrequest", "csrftoken", "csrftoken2", "username", "user_name", "yhm", "xh", "xh_id",
        "studentno", "student_id", "sid"
    )

    private val KEY_VALUE = Regex(
        "(?i)([\"'\\s,{&?])([A-Za-z0-9_.\\-]{1,40})([\"'\\s]*[:=][\"'\\s]*)([^\"'&,\\s}]{1,200})"
    )

    private val LONG_HEX_OR_BASE64 = Regex("\\b[A-Za-z0-9_\\-]{24,}={0,2}\\b")

    private const val MASK = "***"

    /** Redacts a free-form string (log line, exception message, HTTP header dump). */
    fun redact(input: String?): String {
        if (input.isNullOrEmpty()) return input ?: ""
        var out = input
        // Header-style / query-style secrets keyed by name.
        out = KEY_VALUE.replace(out) { m ->
            val key = m.groupValues[2]
            if (isSecretKey(key)) {
                "${m.groupValues[1]}$key${m.groupValues[3]}$MASK"
            } else {
                m.value
            }
        }
        // Cookie pairs without an obvious key name.
        out = COOKIE_PAIR.replace(out) { m ->
            val key = m.groupValues[1]
            if (isSecretKey(key)) "$key=$MASK" else m.value
        }
        // Opaque blobs (session ids, JWTs, SAML assertions) that survived the passes above.
        out = LONG_HEX_OR_BASE64.replace(out, MASK)
        return out
    }

    /** Redacts a header map before it is shown in the diagnostics screen. */
    fun redactHeaders(headers: Map<String, List<String>>): Map<String, List<String>> =
        headers.entries.associate { (name, values) ->
            if (isSecretKey(name)) name to listOf(MASK) else name to values.map { redact(it) }
        }

    /**
     * Keeps only enough of a response body to identify its shape. Never returns the full body: a
     * timetable response contains the student's name and student number.
     */
    fun preview(body: String?, maxChars: Int = 240): String {
        if (body.isNullOrEmpty()) return ""
        val collapsed = body.replace(Regex("\\s+"), " ").trim()
        val head = if (collapsed.length <= maxChars) collapsed else collapsed.take(maxChars) + "…"
        return redact(head)
    }

    /**
     * Redacts a URL. Keeps scheme/host/path so an operator can see *which* endpoint failed, drops the
     * query string values and any fragment.
     */
    fun redactUrl(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        val noFragment = url.substringBefore('#')
        val base = noFragment.substringBefore('?')
        val query = noFragment.substringAfter('?', "")
        if (query.isEmpty()) return base
        val safeQuery = query.split('&').joinToString("&") { pair ->
            val name = pair.substringBefore('=')
            if (isSecretKey(name)) "$name=$MASK" else pair
        }
        return "$base?$safeQuery"
    }

    fun isSecretKey(key: String): Boolean {
        val k = key.trim().lowercase(Locale.ROOT).trim('"', '\'', ':', '=')
        return SECRET_KEYS.any { it == k } || SECRET_KEYS.any { k.contains(it) }
    }
}
