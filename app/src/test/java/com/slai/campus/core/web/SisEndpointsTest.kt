package com.slai.campus.core.web

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 这个类钉住的是「一个地址 → 三个角色」的约定，因为踩过的两个坑都出在这里：
 *
 *  - 把站点根当 baseUrl：登录能过，但请求丢了 `/yjsxt`，课表静默拉不到；
 *  - 把 `/yjsxt` 当登录入口：302 到正方自带的账号密码页，学生登不进去（死胡同）。
 */
class SisEndpointsTest {

    private val defaultOrigin = "https://sis.slai.edu.cn"
    private val defaultBase = "$defaultOrigin/yjsxt"
    private val defaultEntry = "$defaultBase/htxylogin"

    /** 用户在设置里可能填的每一种写法，都必须落到同一个应用根上。 */
    @Test
    fun `site address and app address resolve to the same endpoints`() {
        val inputs = listOf(
            null,
            "",
            "   ",
            defaultOrigin,
            "$defaultOrigin/",
            "sis.slai.edu.cn",
            "sis.slai.edu.cn/",
            "https://SIS.slai.edu.cn",
            defaultBase,
            "$defaultBase/",
            defaultEntry,
            "$defaultBase/xtgl/index_initMenu.html"
        )

        inputs.forEach { input ->
            val endpoints = SisEndpoints.of(input)
            assertThat(endpoints.base).isEqualTo(defaultBase)
            assertThat(endpoints.entry).isEqualTo(defaultEntry)
            assertThat(endpoints.origin).isEqualTo(defaultOrigin)
            assertThat(endpoints.appPath).isEqualTo("/yjsxt")
        }
    }

    /** 站点根地址绝不能被当成 baseUrl：那正是「能登录但拉不到课表」的成因。 */
    @Test
    fun `site address still produces an app scoped base url`() {
        val endpoints = SisEndpoints.of(defaultOrigin)
        assertThat(endpoints.base).isEqualTo(defaultBase)
        // 数据接口全部挂在应用根下面，不能被用户填的站点根挤掉。
        assertThat(endpoints.base + "/kbcx/xskbcx_cxXsKb.html?gnmkdm=index")
            .isEqualTo("$defaultBase/kbcx/xskbcx_cxXsKb.html?gnmkdm=index")
    }

    @Test
    fun `login entry never points at a business path`() {
        // {origin}/yjsxt 未登录时 302 到 /yjsxt/xtgl/login_slogin.html —— 必须走 /htxylogin。
        assertThat(SisEndpoints.of(defaultOrigin).entry).endsWith("/yjsxt/htxylogin")
    }

    @Test
    fun `a deployment with a different app path is kept as typed`() {
        val endpoints = SisEndpoints.of("https://jw.example.edu.cn/jwxt")
        assertThat(endpoints.origin).isEqualTo("https://jw.example.edu.cn")
        assertThat(endpoints.appPath).isEqualTo("/jwxt")
        assertThat(endpoints.base).isEqualTo("https://jw.example.edu.cn/jwxt")
        assertThat(endpoints.entry).isEqualTo("https://jw.example.edu.cn/jwxt/htxylogin")
    }

    @Test
    fun `port and cleartext scheme are normalised`() {
        assertThat(SisEndpoints.of("https://sis.slai.edu.cn:8443/yjsxt").base)
            .isEqualTo("https://sis.slai.edu.cn:8443/yjsxt")
        // 应用禁止明文流量，所以 http 一律升级成 https，而不是原样保留。
        assertThat(SisEndpoints.of("http://sis.slai.edu.cn/yjsxt").base).isEqualTo(defaultBase)
    }

    @Test
    fun `unparseable input falls back instead of failing`() {
        assertThat(SisEndpoints.of("::::").base).isEqualTo(defaultBase)

        val custom = SisEndpoints.of(null, fallback = "https://other.example.edu.cn/app")
        assertThat(custom.base).isEqualTo("https://other.example.edu.cn/app")
        assertThat(custom.entry).isEqualTo("https://other.example.edu.cn/app/htxylogin")
    }

    @Test
    fun `vendor login page is rewritten to the sso entry`() {
        val endpoints = SisEndpoints.of(null)

        assertThat(
            endpoints.ssoFallbackFor("$defaultBase/xtgl/login_slogin.html")
        ).isEqualTo(defaultEntry)
        assertThat(
            endpoints.ssoFallbackFor("$defaultBase/xtgl/login_slogin.html?timeout=true")
        ).isEqualTo(defaultEntry)
    }

    @Test
    fun `ordinary pages and the sso chain are left alone`() {
        val endpoints = SisEndpoints.of(null)

        val untouched = listOf(
            null,
            defaultOrigin,
            defaultBase,
            endpoints.entry,
            "https://sts.slai.edu.cn/adfs/oauth2/authorize?client_id=x",
            "$defaultBase/xtgl/index_initMenu.html",
            "$defaultBase/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=index"
        )

        untouched.forEach { url ->
            assertThat(endpoints.ssoFallbackFor(url)).isNull()
        }
    }
}
