package com.slai.campus.core.web

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

enum class AutomaticLoginState { IDLE, RUNNING, SUCCEEDED, FAILED }

/** The visible page and the off-screen page share cookies, but never run at the same time. */
internal interface SilentLoginPage {
    val url: String?
    val failed: Boolean
    val loaded: Boolean
    val generation: Int
    suspend fun inspect(): String
    suspend fun submit(password: Boolean): String
}

internal object SilentLoginUrls {
    fun allowed(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme.equals("https", true) && uri.host in setOf("stu.slai.edu.cn", "sts.slai.edu.cn") &&
            uri.rawUserInfo == null && uri.port in setOf(-1, 443)
    }.getOrDefault(false)
}

/** Live protected requests decide success; page load events only drive form interaction. */
internal suspend fun runSilentLogin(
    page: SilentLoginPage,
    beginPasswordAttempt: suspend () -> Boolean,
    confirmSession: suspend () -> Boolean,
    timeoutMs: Long = 30_000,
    pollMs: Long = 400,
    authorizeCredentials: suspend () -> Boolean = { true },
    onManualRequired: () -> Unit = {}
): Boolean {
    if (page.failed || (page.url != null && page.url != "about:blank" && !SilentLoginUrls.allowed(page.url))) return false
    return withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            val confirmed = async(start = CoroutineStart.LAZY) {
                while (true) {
                    if (withTimeoutOrNull(4_000) { confirmSession() } == true) return@async true
                    delay(1_500)
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
            val browser = async(start = CoroutineStart.LAZY) {
                interactWithLoginPage(page, beginPasswordAttempt, pollMs, authorizeCredentials, onManualRequired)
            }
            try {
                select {
                    confirmed.onAwait { it }
                    // A callback/renderer error must not beat an already usable API session.
                    browser.onAwait { withTimeoutOrNull(4_000) { confirmed.await() } ?: false }
                }
            } finally {
                confirmed.cancel()
                browser.cancel()
            }
        }
    } ?: false
}

private suspend fun interactWithLoginPage(
    page: SilentLoginPage,
    beginPasswordAttempt: suspend () -> Boolean,
    pollMs: Long,
    authorizeCredentials: suspend () -> Boolean,
    onManualRequired: () -> Unit
) {
    val attempt = SchoolLoginAttempt()
    var passwordGeneration: Int? = null
    var credentialsAuthorized = false
    while (true) {
        if (page.failed) return
        val url = page.url
        if (url != null && url != "about:blank") {
            if (!SilentLoginUrls.allowed(url)) return
            if (page.loaded && SchoolLoginScript.isTrustedLogin(url)) {
                when (val step = page.inspect()) {
                    "manual" -> { onManualRequired(); return }
                    "refused" -> return
                    "username", "password" -> {
                        if (!credentialsAuthorized) {
                            if (!authorizeCredentials()) return
                            credentialsAuthorized = true
                        }
                        val password = step == "password"
                        if (password && passwordGeneration != null && passwordGeneration != page.generation) return
                        if (attempt.claim(password)) {
                            if (password) {
                                if (!beginPasswordAttempt()) return
                                passwordGeneration = page.generation
                            }
                            if (page.submit(password) != "submitted") return
                        }
                    }
                }
            }
        }
        delay(pollMs)
    }
}
