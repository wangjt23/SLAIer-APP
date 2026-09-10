package com.slai.campus.data.sis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [SisConfig] 只做一件事：把「设置里那一个地址」翻译成实际要用的地址。翻译错了会同时打坏登录和课表，
 * 所以两种填法都必须落在同一组端点上。
 */
class SisConfigTest {

    private val base = "https://sis.slai.edu.cn/yjsxt"

    @Test
    fun `base url accepts either the site address or the app address`() {
        listOf(null, "", "https://sis.slai.edu.cn", "$base/", "sis.slai.edu.cn/yjsxt").forEach { input ->
            assertThat(SisConfig.baseUrlOrDefault(input)).isEqualTo(base)
        }
    }

    @Test
    fun `timetable endpoint keeps the app prefix`() {
        val baseUrl = SisConfig.baseUrlOrDefault("https://sis.slai.edu.cn")
        val endpoint = SisConfig.apiUrl(baseUrl, SisConfig.timetableApiCandidates.first())

        assertThat(endpoint).isEqualTo("$base/kbcx/xskbcx_cxXsKb.html?gnmkdm=index")
    }

    @Test
    fun `login entry is the sso endpoint, not the vendor form`() {
        assertThat(SisConfig.entryUrlOrDefault("https://sis.slai.edu.cn")).isEqualTo("$base/htxylogin")
        assertThat(SisConfig.entryUrlFor(base)).isEqualTo("$base/htxylogin")
        // 未登录访问业务路径会 302 到这里，它只是「会话过期」的标记，不是登录入口。
        assertThat(SisConfig.LOGIN_PAGE_PATH).isEqualTo("/xtgl/login_slogin.html")
    }
}
