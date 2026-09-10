package com.slai.campus.navigation

import com.slai.campus.core.common.SchoolSystem

/**
 * 全屏浮层在**进程重建后**的恢复策略。
 *
 * 背景：`CampusRoot` 里 `selectedTab` 用了 `rememberSaveable`，紧邻的 `overlay` 只用了 `remember`，
 * 于是进程被系统回收后重建会得到一个"tab 还在、浮层没了"的半状态（诊断页开着、回来一看没了）。
 *
 * 但不是所有浮层都该恢复：
 *
 *  - **登录流程**（`isLoginFlow`）不恢复 —— 进程重建后自动重开登录页并不是用户要的，
 *    他刚回来多半只是想看课表；
 *  - **抓包**（`captureEnabled`）不恢复 —— 抓到的请求缓冲本来就随进程没了，
 *    重开一个空的录制会话没有意义；
 *  - **浏览类页面**（"打开教务系统"等）恢复 —— 那是用户自己要看的东西，和浏览器恢复标签页同理；
 *  - 诊断页 / API Provider 页恢复。
 *
 * 编解码写成纯函数（不碰 Compose、不碰 Bundle），这样它能在纯 JVM 单测里被钉住 ——
 * 这类"只在异常路径上生效"的逻辑，没有测试就等于没有。
 */
object OverlayPersistence {

    private const val KIND_DIAGNOSTICS = "diagnostics"
    private const val KIND_PROVIDERS = "providers"
    private const val KIND_WEB = "web"

    /** 编码成 Bundle 放得下的字符串列表；返回空列表表示"这个浮层不该被恢复"。 */
    fun encode(overlay: Overlay?): List<String> = when (overlay) {
        null -> emptyList()
        Overlay.Diagnostics -> arrayListOf(KIND_DIAGNOSTICS)
        Overlay.Providers -> arrayListOf(KIND_PROVIDERS)
        is Overlay.Web ->
            if (overlay.isLoginFlow || overlay.captureEnabled) {
                emptyList()
            } else {
                arrayListOf(
                    KIND_WEB,
                    overlay.url,
                    overlay.title,
                    overlay.system?.name.orEmpty()
                )
            }
    }

    /** [encode] 的逆运算。任何无法识别的输入都退回 null（= 不显示浮层），绝不抛异常。 */
    fun decode(saved: List<String>): Overlay? = when (saved.firstOrNull()) {
        KIND_DIAGNOSTICS -> Overlay.Diagnostics
        KIND_PROVIDERS -> Overlay.Providers
        KIND_WEB -> {
            val url = saved.getOrNull(1)
            val title = saved.getOrNull(2)
            if (url.isNullOrBlank() || title == null) {
                null
            } else {
                Overlay.Web(
                    url = url,
                    title = title,
                    system = saved.getOrNull(3)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { name -> runCatching { SchoolSystem.valueOf(name) }.getOrNull() },
                    // 恢复出来的永远是"浏览"语义：登录流程与抓包浮层根本不会被编码。
                    isLoginFlow = false,
                    captureEnabled = false
                )
            }
        }
        else -> null
    }
}
