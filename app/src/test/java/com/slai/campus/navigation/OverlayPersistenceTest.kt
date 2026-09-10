package com.slai.campus.navigation

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.SchoolSystem
import org.junit.Test

/**
 * 浮层恢复策略的契约。这些分支只在"进程被系统回收后重建"时才走到 —— 手测极难覆盖，
 * 所以用单测把"该恢复什么、不该恢复什么"钉死。
 */
class OverlayPersistenceTest {

    private val browse = Overlay.Web(
        url = "https://sis.slai.edu.cn/yjsxt/xtgl/index_initMenu.html",
        title = "教务系统首页",
        system = SchoolSystem.SIS,
        isLoginFlow = false,
        captureEnabled = false
    )

    @Test
    fun `browsing overlay survives a round trip`() {
        val restored = OverlayPersistence.decode(OverlayPersistence.encode(browse))

        assertThat(restored).isEqualTo(browse)
        // 恢复出来的一定是"浏览"语义，绝不能带上登录流程或抓包标记。
        val web = restored as Overlay.Web
        assertThat(web.isLoginFlow).isFalse()
        assertThat(web.captureEnabled).isFalse()
        assertThat(web.closeOnLogin).isFalse()
    }

    @Test
    fun `tool overlays survive a round trip`() {
        assertThat(OverlayPersistence.decode(OverlayPersistence.encode(Overlay.Diagnostics)))
            .isEqualTo(Overlay.Diagnostics)
        assertThat(OverlayPersistence.decode(OverlayPersistence.encode(Overlay.Providers)))
            .isEqualTo(Overlay.Providers)
    }

    @Test
    fun `overlay without a system still round trips`() {
        val page = Overlay.Web(url = "https://sts.slai.edu.cn/adfs/ls", title = "登录", system = null)

        val restored = OverlayPersistence.decode(OverlayPersistence.encode(page)) as Overlay.Web
        assertThat(restored.url).isEqualTo(page.url)
        assertThat(restored.system).isNull()
    }

    /** 登录流程刻意不恢复：进程重建后自动重开登录页不是用户要的。 */
    @Test
    fun `login flow overlay is deliberately not restored`() {
        val login = Overlay.Web(
            url = "https://sis.slai.edu.cn/yjsxt/htxylogin",
            title = "登录教务系统",
            system = SchoolSystem.SIS,
            isLoginFlow = true
        )

        assertThat(OverlayPersistence.encode(login)).isEmpty()
        assertThat(OverlayPersistence.decode(OverlayPersistence.encode(login))).isNull()
    }

    /** 抓包浮层同理：请求缓冲已经随进程消失，重开一个空录制会话没有意义。 */
    @Test
    fun `capture overlay is deliberately not restored`() {
        val capture = Overlay.Web(
            url = "https://sis.slai.edu.cn/yjsxt/xtgl/index_initMenu.html",
            title = "抓取课表请求",
            system = SchoolSystem.SIS,
            captureEnabled = true
        )

        assertThat(OverlayPersistence.encode(capture)).isEmpty()
    }

    @Test
    fun `nothing to restore decodes to no overlay`() {
        assertThat(OverlayPersistence.encode(null)).isEmpty()
        assertThat(OverlayPersistence.decode(emptyList())).isNull()
    }

    /** 存档来自旧版本 / 被截断时不能崩，只是不显示浮层。 */
    @Test
    fun `malformed save data degrades instead of throwing`() {
        assertThat(OverlayPersistence.decode(listOf("web"))).isNull()
        assertThat(OverlayPersistence.decode(listOf("web", "", "标题"))).isNull()
        assertThat(OverlayPersistence.decode(listOf("something-else"))).isNull()

        val unknownSystem = OverlayPersistence.decode(
            listOf("web", "https://sis.slai.edu.cn/yjsxt/", "教务", "NOT_A_SYSTEM")
        ) as Overlay.Web
        assertThat(unknownSystem.system).isNull()
    }
}
