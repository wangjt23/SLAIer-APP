package com.slai.campus.data.stu

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class StuSessionRecoveryTest {
    private fun client(routes: Map<String, Pair<Int, String>>, visited: MutableList<String>): OkHttpClient =
        OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            val url = chain.request().url.toString()
            visited += url
            val (code, content) = requireNotNull(routes[url]) { "Unexpected request: $url" }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .apply { if (code in 300..399) header("Location", content) }
                .body((if (code == 200) content else "").toResponseBody()).build()
        }.build()

    @Test fun `expired business session renews through existing SSO and returns to STU`() {
        val visited = mutableListOf<String>()
        val client = client(mapOf(
            "https://stu.slai.edu.cn/sso/login" to (302 to "https://sts.slai.edu.cn/adfs/oauth2/authorize"),
            "https://sts.slai.edu.cn/adfs/oauth2/authorize" to (302 to "https://stu.slai.edu.cn/sso/code?code=fake"),
            "https://stu.slai.edu.cn/sso/code?code=fake" to (302 to "/a"),
            "https://stu.slai.edu.cn/a" to (200 to "学生工作管理系统")
        ), visited)
        assertThat(renewStuSession(client, "https://stu.slai.edu.cn")).isTrue()
        assertThat(visited).hasSize(4)
    }

    @Test fun `SSO login form requires manual login and is never submitted`() {
        val visited = mutableListOf<String>()
        val client = client(mapOf(
            "https://stu.slai.edu.cn/sso/login" to (302 to "https://sts.slai.edu.cn/adfs/oauth2/authorize"),
            "https://sts.slai.edu.cn/adfs/oauth2/authorize" to (200 to "<input type='password'>")
        ), visited)
        assertThat(renewStuSession(client, "https://stu.slai.edu.cn")).isFalse()
        assertThat(visited).hasSize(2)
    }

    @Test fun `redirect loops stop without clearing any session`() {
        val visited = mutableListOf<String>()
        val client = client(mapOf("https://stu.slai.edu.cn/sso/login" to (302 to "/sso/login")), visited)
        assertThat(renewStuSession(client, "https://stu.slai.edu.cn")).isFalse()
        assertThat(visited).hasSize(1)
    }

    @Test fun `redirects cannot leave the two SSO hosts or downgrade HTTPS`() {
        listOf("https://unrelated.example/a", "http://stu.slai.edu.cn/a").forEach { target ->
            val visited = mutableListOf<String>()
            val client = client(mapOf("https://stu.slai.edu.cn/sso/login" to (302 to target)), visited)
            assertThat(renewStuSession(client, "https://stu.slai.edu.cn")).isFalse()
            assertThat(visited).hasSize(1)
        }
    }

    @Test fun `API redirects to either login entry are recognized`() {
        assertThat(StuCheckInParser.isLoginUrl("/sso/login")).isTrue()
        assertThat(StuCheckInParser.isLoginUrl("/a/login;JSESSIONID=fake")).isTrue()
        assertThat(StuCheckInParser.isLoginUrl("https://sts.slai.edu.cn/adfs/oauth2/authorize")).isTrue()
        assertThat(StuCheckInParser.isLoginUrl("/a/edu/acm/swipe/list")).isFalse()
    }
}
