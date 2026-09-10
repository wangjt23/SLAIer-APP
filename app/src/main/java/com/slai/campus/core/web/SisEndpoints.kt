package com.slai.campus.core.web

import com.slai.campus.BuildConfig
import java.net.URI

/**
 * 教务系统的「三个地址」，以及从用户填的那**一个**地址推出它们的方法。
 *
 * 实测（2026-09-10，未登录直连）表明这套系统并不是一个 URL，而是三个，混用它们会同时打坏
 * 「首次登录」和「拉取课表」：
 *
 * | 角色 | 值 | 行为 |
 * |------|-----|------|
 * | origin  | `https://sis.slai.edu.cn` | 站点根。`GET /` 返回一个 290 字节的静态页，唯一作用是 `location.href = …/yjsxt/htxylogin` |
 * | appPath | `/yjsxt` | 正方（ZFSoft）应用根。所有业务页面和所有 JSON 接口都挂在它下面 |
 * | entry   | `{base}/htxylogin` | 登录入口：`302 -> sts.slai.edu.cn/adfs/oauth2/authorize`（不依赖 JS，与站点根跳转的落地 URL 完全一致） |
 *
 * 这个类要防的两个坏情况：
 *
 * 1. **把 origin 当 baseUrl 用**（设置里填 `https://sis.slai.edu.cn`）：SSO 登录照样能过，但每个请求都
 *    丢了 `/yjsxt` 前缀，`/kbcx/xskbcx_cxXsKb.html` 已经不是课表接口了，课表于是静默拉不到。用户
 *    一旦按「登录要从严地址进」的说法手动改地址，就会正好踩中这个坑。
 * 2. **把业务路径当登录入口用**（`{origin}/yjsxt`）：`302 -> /yjsxt/` → `302 -> /yjsxt/xtgl/login_slogin.html`，
 *    也就是正方自带的账号密码表单。它没有任何通往统一身份认证的链接，学生也没有本地密码，进去就是
 *    死胡同 —— 表现就是「进去之后的登录界面不是学校那个，登不进去」。[ssoFallbackFor] 专门救这一种。
 */
data class SisEndpoints(
    /** 站点根，例如 `https://sis.slai.edu.cn`。永远不带结尾斜杠。 */
    val origin: String,
    /** 正方应用路径，默认 `/yjsxt`。 */
    val appPath: String,
    /** 应用根 = `origin + appPath`，所有业务页面与数据接口的前缀。 */
    val base: String,
    /** 统一身份认证入口 = `base + /htxylogin`。 */
    val entry: String
) {
    /** 正方自带的账号密码页：能打开，但对只走 SSO 的账号毫无用处。 */
    fun isVendorLoginPage(url: String?): Boolean =
        url?.contains(VENDOR_LOGIN_PATH, ignoreCase = true) == true

    /**
     * 当学校把用户丢到一个没用的页面上时，应该改为加载的 URL；[url] 正常时返回 null。
     *
     * 刻意做得很窄：只改写正方自带的登录表单，正常页面、真正的报错页、以及 SSO 链路本身都不碰，
     * 所以调用方不可能因此陷入循环。
     */
    fun ssoFallbackFor(url: String?): String? = when {
        url == null -> null
        isVendorLoginPage(url) -> entry
        else -> null
    }

    companion object {
        const val DEFAULT_APP_PATH = "/yjsxt"

        /** 统一身份认证入口路径，相对 [appPath]。 */
        const val SSO_ENTRY_PATH = "/htxylogin"

        /** 正方自带登录页。未登录访问任何业务路径都会 302 到这里。 */
        const val VENDOR_LOGIN_PATH = "/xtgl/login_slogin.html"

        private const val LAST_RESORT = "https://sis.slai.edu.cn"

        /**
         * 把用户在「设置 → 高级 → 教务系统地址」里填的东西规范化。
         *
         * 下面这些写法都得到同一个结果（`base = https://sis.slai.edu.cn/yjsxt`）：
         * `sis.slai.edu.cn`、`https://sis.slai.edu.cn`、`https://sis.slai.edu.cn/`、
         * `https://sis.slai.edu.cn/yjsxt`、`https://sis.slai.edu.cn/yjsxt/htxylogin`。
         * 换成别的部署（应用路径不是 `/yjsxt`）时按用户填的路径走。
         *
         * 空值或无法解析时退回 [fallback]（构建期的默认地址），再不行才用写死的站点根 ——
         * 这个函数永远不返回 null。
         */
        fun of(configured: String?, fallback: String = BuildConfig.DEFAULT_SIS_BASE_URL): SisEndpoints =
            parse(configured)
                ?: parse(fallback)
                ?: build(LAST_RESORT, DEFAULT_APP_PATH)

        private fun build(origin: String, appPath: String) = SisEndpoints(
            origin = origin,
            appPath = appPath,
            base = origin + appPath,
            entry = origin + appPath + SSO_ENTRY_PATH
        )

        private fun parse(raw: String?): SisEndpoints? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null

            // 允许只填主机名；协议一律升级为 https，因为应用本身禁止明文流量。
            val withScheme = if (SCHEME_PREFIX.containsMatchIn(text)) text else "https://$text"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            return build("https://${host.lowercase()}$port", appPathOf(uri.path))
        }

        /** `/yjsxt/htxylogin`、`/yjsxt/`、``、`/` 都表示「应用在 `/yjsxt`」。 */
        private fun appPathOf(path: String?): String {
            val segments = path.orEmpty().split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return DEFAULT_APP_PATH
            val marker = DEFAULT_APP_PATH.trimStart('/')
            val index = segments.indexOfFirst { it.equals(marker, ignoreCase = true) }
            // 截到应用根为止：更深的路径（/yjsxt/xtgl/…）是应用内部页面，不属于 baseUrl。
            return "/" + segments.take(if (index >= 0) index + 1 else segments.size).joinToString("/")
        }

        private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    }
}
