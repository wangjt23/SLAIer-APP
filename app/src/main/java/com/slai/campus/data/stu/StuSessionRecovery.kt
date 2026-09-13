package com.slai.campus.data.stu

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Each redirect is a fresh call, so the cookie interceptor reads cookies for that host only. */
internal fun renewStuSession(apiClient: OkHttpClient, baseUrl: String): Boolean {
    val base = baseUrl.toHttpUrlOrNull() ?: return false
    var url = "${baseUrl.trimEnd('/')}/sso/login".toHttpUrlOrNull() ?: return false
    val visited = mutableSetOf<String>()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    repeat(10) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0 || !url.isHttps ||
            (url.host != base.host && url.host != "sts.slai.edu.cn") ||
            !visited.add(url.toString())
        ) return false
        val request = Request.Builder().url(url).get()
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Cache-Control", "no-cache").build()
        val call = apiClient.newCall(request)
        call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
        val response = try { call.execute() } catch (_: java.io.IOException) {
            return false
        }
        response.use { res ->
            if (res.code in 300..399) {
                url = res.header("Location")?.let(url::resolve) ?: return false
            } else {
                val body = try { res.peekBody(64 * 1024).string() } catch (_: java.io.IOException) {
                    return false
                }
                return res.isSuccessful && url.host == base.host &&
                    !StuCheckInParser.isLoginUrl(url.toString()) &&
                    !StuCheckInParser.looksLikeLoginPage(body)
            }
        }
    }
    return false
}
