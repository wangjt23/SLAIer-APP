package com.slai.campus.core.web

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One request/response pair observed while a school page ran in a WebView.
 *
 * Recorded by hooking the page's own `XMLHttpRequest` and `fetch`. Nothing is guessed: the URL,
 * method, request body and response body are exactly what the page itself used, which is what makes
 * "learn the endpoint" possible without credentials or manual DevTools work.
 */
@Serializable
data class CaptureRecord(
    val method: String = "GET",
    val url: String = "",
    @SerialName("reqBody") val requestBody: String? = null,
    val status: Int? = null,
    val body: String = "",
    /** Page URL that was loaded when this request fired. */
    val pageUrl: String? = null
) {
    val isJson: Boolean
        get() {
            val trimmed = body.trimStart()
            return trimmed.startsWith("{") || trimmed.startsWith("[")
        }

    val host: String? get() = AllowedHosts.hostOf(url)
}

/**
 * A request seen by `WebViewClient.shouldInterceptRequest`.
 *
 * This exists because the XHR hook only runs in the frame it was injected into. ZFSoft's shell page
 * loads each module inside an **iframe**, so a timetable request made by an iframe is invisible to the
 * main-frame hook. `shouldInterceptRequest` sees every frame's requests, so it is the safety net that
 * still tells us which URLs the page asked for.
 */
@Serializable
data class InterceptedRequest(
    val method: String = "GET",
    val url: String = "",
    val mainFrame: Boolean = true
)

/** Everything a capture session produced. */
data class CaptureSession(
    /** XHR/fetch calls with their response bodies (main frame only). */
    val records: List<CaptureRecord> = emptyList(),
    /** Every request, including iframe sub-resources. */
    val requests: List<InterceptedRequest> = emptyList()
) {
    val isEmpty: Boolean get() = records.isEmpty() && requests.isEmpty()

    /** Distinct host+path of every request, in order — the best clue for a hidden endpoint. */
    fun urlIndex(): List<String> = requests.map { it.url.substringAfter("://", it.url).substringBefore('?') }.distinct()
}

/**
 * The capture hook, shared by the headless extractor and the visible "learn" WebView so both observe
 * exactly the same things.
 *
 * Deliberately not a JavaScript bridge: the hook only writes into a page-global array, and the native
 * side reads it back with `evaluateJavascript`. A page therefore has no way to call into the app.
 */
object CaptureScript {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private const val BODY_LIMIT = 2_000_000
    private const val REQUEST_BODY_LIMIT = 8_000

    val INSTALL: String = """
        (function(){
          try {
            if (window.__campusCaptureInstalled) return;
            window.__campusCaptureInstalled = true;
            window.__campusCapture = [];
            var LIMIT = $BODY_LIMIT;
            var REQ = $REQUEST_BODY_LIMIT;
            function push(rec) {
              try {
                window.__campusCapture.push(rec);
                if (window.__campusCapture.length > 60) window.__campusCapture.shift();
              } catch (e) {}
            }
            function page() { try { return String(location.href).slice(0, 500); } catch (e) { return ''; } }
            var open = XMLHttpRequest.prototype.open;
            var send = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(m, u) {
              try { this.__campusMethod = m; this.__campusUrl = u; } catch (e) {}
              return open.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function(body) {
              var self = this;
              try {
                this.addEventListener('load', function() {
                  try {
                    var t = self.responseText;
                    if (typeof t !== 'string') return;
                    push({
                      method: String(self.__campusMethod || 'GET').toUpperCase(),
                      url: String(self.__campusUrl || '').slice(0, 1000),
                      reqBody: (typeof body === 'string') ? body.slice(0, REQ) : '',
                      status: self.status,
                      body: t.slice(0, LIMIT),
                      pageUrl: page()
                    });
                  } catch (e) {}
                });
              } catch (e) {}
              return send.apply(this, arguments);
            };
            if (window.fetch) {
              var origFetch = window.fetch;
              window.fetch = function(input, init) {
                var u = '', m = 'GET', rb = '';
                try {
                  u = (input && input.url) ? input.url : String(input);
                  m = (init && init.method) || (input && input.method) || 'GET';
                  rb = (init && typeof init.body === 'string') ? init.body.slice(0, REQ) : '';
                } catch (e) {}
                return origFetch.apply(this, arguments).then(function(resp) {
                  try {
                    resp.clone().text().then(function(t) {
                      push({
                        method: String(m).toUpperCase(),
                        url: String(u).slice(0, 1000),
                        reqBody: rb,
                        status: resp.status,
                        body: String(t).slice(0, LIMIT),
                        pageUrl: page()
                      });
                    });
                  } catch (e) {}
                  return resp;
                });
              };
            }
          } catch (e) {}
        })();
    """.trimIndent()

    /** Reads the accumulated array back into native memory. */
    const val READ: String = "JSON.stringify(window.__campusCapture || [])"

    const val RESET: String = "window.__campusCapture = []; 'ok'"

    /**
     * `WebView.evaluateJavascript` returns the JS value JSON-encoded, so a string result arrives
     * wrapped in quotes with escaped inner quotes. Unwrap before parsing.
     */
    fun parseEvaluated(raw: String?): List<CaptureRecord> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        val unescaped = runCatching {
            (json.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull
        }.getOrNull() ?: raw
        return parse(unescaped)
    }

    fun parse(rawJson: String?): List<CaptureRecord> {
        if (rawJson.isNullOrBlank() || rawJson == "null") return emptyList()
        return runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CaptureRecord.serializer()), rawJson)
        }.getOrElse { emptyList() }
    }

    fun toJson(records: List<CaptureRecord>): String = runCatching {
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(CaptureRecord.serializer()), records)
    }.getOrElse { "[]" }
}
