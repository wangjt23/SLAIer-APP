package com.slai.campus.core.network

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.session.CookieSessionGuard
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class WebViewCookieInterceptorTest {
    @Test fun `flow revoked while waiting to commit cannot write even with unchanged fresh cookie snapshot`() {
        val guard = CookieSessionGuard()
        var permitted = true
        var written = false
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> bridgeWebViewCookies(chain,
                { guard.snapshot("stu.slai.edu.cn", "session=fresh") },
                { _, snapshot, _ ->
                    // Equivalent to logout winning while the main-thread commit is queued.
                    permitted = false
                    written = snapshot != null && guard.claimWrite(snapshot, "session=fresh")
                }) }
            .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Set-Cookie", "session=stale-oauth; Path=/")
                .body("{}".toResponseBody()).build() }.build()
        client.newCall(Request.Builder().url("https://stu.slai.edu.cn/sso/code?code=fake")
            .tag(CookieWritePermit::class.java, CookieWritePermit { permitted }).build()).execute().close()
        assertThat(written).isFalse()
    }

    @Test fun `expired probe cannot overwrite cookies established by concurrent login`() {
        var cookies = "session=expired"
        val guard = CookieSessionGuard()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> bridgeWebViewCookies(chain, { guard.snapshot("stu.slai.edu.cn", cookies) }, { _, snapshot, values ->
                if (snapshot != null && guard.claimWrite(snapshot, cookies)) cookies = values.single()
            }) }
            .addInterceptor { chain ->
                assertThat(chain.request().header("Cookie")).isEqualTo("session=expired")
                // Browser login finishes while this old probe is still in flight.
                cookies = "session=authenticated"
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(302).message("Login required").header("Location", "https://stu.slai.edu.cn/sso/login")
                    .header("Set-Cookie", "session=anonymous; Path=/").body("".toResponseBody()).build()
            }.followRedirects(false).build()
        val request = Request.Builder().url("https://stu.slai.edu.cn/a/attendance")
            .tag(ReadOnlySessionCheck::class.java, ReadOnlySessionCheck).build()
        client.newCall(request).execute().use { assertThat(it.code).isEqualTo(302) }
        assertThat(cookies).isEqualTo("session=authenticated")
    }

    @Test fun `ordinary requests still persist renewed cookies`() {
        var saved: List<String>? = null
        val guard = CookieSessionGuard()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> bridgeWebViewCookies(chain, { guard.snapshot("stu.slai.edu.cn", null) }, { _, snapshot, values ->
                if (snapshot != null && guard.claimWrite(snapshot, null)) saved = values
            }) }
            .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Set-Cookie", "session=renewed; Path=/")
                .body("{}".toResponseBody()).build() }.build()
        client.newCall(Request.Builder().url("https://stu.slai.edu.cn/a/attendance").build()).execute().close()
        assertThat(saved).containsExactly("session=renewed; Path=/")
    }

    @Test fun `ordinary expired request cannot overwrite concurrent browser login`() {
        var cookies = "session=old"
        val guard = CookieSessionGuard()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> bridgeWebViewCookies(chain, { guard.snapshot("stu.slai.edu.cn", cookies) }, { _, snapshot, values ->
                if (snapshot != null && guard.claimWrite(snapshot, cookies)) cookies = values.single()
            }) }
            .addInterceptor { chain ->
                val browser = guard.beginBrowser()
                cookies = "session=authenticated"
                guard.endBrowser(browser)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").header("Set-Cookie", "session=anonymous; Path=/")
                    .body("{}".toResponseBody()).build()
            }.build()
        client.newCall(Request.Builder().url("https://stu.slai.edu.cn/a/attendance").build()).execute().close()
        assertThat(cookies).isEqualTo("session=authenticated")
    }

    @Test fun `no applicable cookie removes cookie copied from a previous redirect`() {
        val guard = CookieSessionGuard()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> bridgeWebViewCookies(chain, { guard.snapshot("stu.slai.edu.cn", null) }, { _, _, _ -> }) }
            .addInterceptor { chain ->
                assertThat(chain.request().header("Cookie")).isNull()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("{}".toResponseBody()).build()
            }.build()
        client.newCall(Request.Builder().url("https://sts.slai.edu.cn/adfs/ls")
            .header("Cookie", "jeesite.session.id=stu-only").build()).execute().close()
    }

    @Test fun `generated referer never contains OAuth query or fragment`() {
        val request = bridgedRequest("https://stu.slai.edu.cn/sso/code?code=secret-code&state=private-state#private-fragment")
        assertThat(request.header("Referer")).isEqualTo("https://stu.slai.edu.cn/")
    }

    @Test fun `same origin referer preserves page path without query or fragment`() {
        val request = bridgedRequest("https://stu.slai.edu.cn/a/attendance",
            "https://stu.slai.edu.cn/a/page?token=private#fragment")
        assertThat(request.header("Referer")).isEqualTo("https://stu.slai.edu.cn/a/page")
    }

    @Test fun `another origins referer is replaced for a cookie host and removed otherwise`() {
        val carried = "https://stu.slai.edu.cn/sso/code?code=secret"
        assertThat(bridgedRequest("https://sts.slai.edu.cn/adfs/ls", carried).header("Referer"))
            .isEqualTo("https://sts.slai.edu.cn/")
        assertThat(bridgedRequest("https://external.example.invalid/final", carried, null).header("Referer")).isNull()
        assertThat(bridgedRequest("https://stu.slai.edu.cn:8443/final", carried).header("Referer"))
            .isEqualTo("https://stu.slai.edu.cn:8443/")
    }

    private fun bridgedRequest(url: String, referer: String? = null, cookie: String? = "session=local-test"): Request {
        val guard = CookieSessionGuard()
        lateinit var sent: Request
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> bridgeWebViewCookies(chain,
                { guard.snapshot(it.toHttpUrl().host, cookie) }, { _, _, _ -> }) }
            .addInterceptor { chain ->
                sent = chain.request()
                Response.Builder().request(sent).protocol(Protocol.HTTP_1_1)
                    .code(200).message("local fixture").body("{}".toResponseBody()).build()
            }.build()
        val request = Request.Builder().url(url).apply { if (referer != null) header("Referer", referer) }.build()
        client.newCall(request).execute().close()
        return sent
    }
}
