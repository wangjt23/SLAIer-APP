package com.slai.campus.core.network

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class WebViewCookieInterceptorTest {
    @Test fun `expired probe cannot overwrite cookies established by concurrent login`() {
        var cookies = "session=expired"
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> bridgeWebViewCookies(chain, { cookies }, { _, values -> cookies = values.single() }) }
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
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> bridgeWebViewCookies(chain, { null }, { _, values -> saved = values }) }
            .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Set-Cookie", "session=renewed; Path=/")
                .body("{}".toResponseBody()).build() }.build()
        client.newCall(Request.Builder().url("https://stu.slai.edu.cn/a/attendance").build()).execute().close()
        assertThat(saved).containsExactly("session=renewed; Path=/")
    }
}
