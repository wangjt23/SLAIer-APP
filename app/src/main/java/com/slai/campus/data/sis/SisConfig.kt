package com.slai.campus.data.sis

import com.slai.campus.BuildConfig
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.web.SisEndpoints

/**
 * Everything we know about the school's academic-affairs system, in one place.
 *
 * Reconnaissance (unauthenticated, 2026-09-09; re-checked 2026-09-10) established:
 *  - `https://sis.slai.edu.cn/` is a JS redirector to `/yjsxt/htxylogin`;
 *  - `/yjsxt` alone is **not** an entry: it answers `302 -> /yjsxt/` →
 *    `302 -> /yjsxt/xtgl/login_slogin.html`, the vendor's own password form, which has no link to
 *    the identity provider. Anyone who lands there is stuck — see [SisEndpoints];
 *  - `/yjsxt/htxylogin` answers `302 -> sts.slai.edu.cn/adfs/oauth2/authorize` with
 *    `client_id=74c2df64-…`, `redirect_uri=…/yjsxt/htxylogin` (AD FS OAuth 2.0 authorization code);
 *  - the application behind it is **ZFSoft v5** (`/yjsxt/xtgl/login_slogin.html`,
 *    `_stylePath = /zftal-ui-v5-1.0.2`, `<meta name="Copyright" content="zfsoft">`,
 *    page title 深圳河套学院-教务管理系统);
 *  - any unauthenticated `/yjsxt/<path>` request answers `302 -> /yjsxt/xtgl/login_slogin.html`;
 *  - the session cookie is `JSESSIONID`, scoped `Path=/yjsxt`, `HttpOnly`.
 *
 * The two timetable endpoints below are the standard ZFSoft v5 student timetable endpoints and both
 * answer `302` (i.e. they exist and are protected) on this instance. They are listed as *candidates*
 * because a school can rename or disable a module: the data source tries each in order and only
 * accepts a response that passes schema validation, and the app falls back to WebView extraction and
 * then to the web page itself if none of them work.
 */
object SisConfig {

    const val PARSER_VERSION = "sis-zfsoft-v5-1"

    val system = SchoolSystem.SIS

    /** Base path of the ZFSoft application. Overridable in Settings. */
    val defaultBaseUrl: String = BuildConfig.DEFAULT_SIS_BASE_URL

    /**
     * 构建期默认地址对应的登录入口。
     *
     * 正常情况下入口由 [entryUrlFor] 从**当前** baseUrl 推出来（用户在设置里改地址时它得跟着走），
     * 这个常量只在「还没有 baseUrl 可用」时兜底。见 [SisEndpoints]。
     */
    val defaultEntryUrl: String = SisEndpoints.of(null).entry

    /** Where an unauthenticated request lands. Presence of this marker == session expired. */
    const val LOGIN_PAGE_PATH = SisEndpoints.VENDOR_LOGIN_PATH

    /** Page title of the ZFSoft login screen; used for the "200 but it's the login page" case. */
    const val LOGIN_PAGE_TITLE = "教务管理系统"

    /**
     * gnmkdm (功能模块代码)。
     *
     * Live capture (2026-09-09, real login) shows this deployment uses `index` for the timetable
     * endpoint, not the vendor-default `N253508`.
     */
    const val GNMKDM_TIMETABLE = "index"

    /**
     * Candidate JSON endpoints for "my timetable". Ordered by likelihood; each is validated.
     * `{base}` is replaced with the configured base URL.
     */
    val timetableApiCandidates: List<String> = listOf(
        // Confirmed against the live system.
        "{base}/kbcx/xskbcx_cxXsKb.html?gnmkdm=$GNMKDM_TIMETABLE",
        "{base}/xskbcx/xskbcx_cxXsKb.html?gnmkdm=$GNMKDM_TIMETABLE",
        "{base}/xskbcx/xskbcx_cxXsKbHtml.html?gnmkdm=$GNMKDM_TIMETABLE"
    )

    /** Form body for the timetable endpoint. Confirmed against the live system. */
    fun timetableBody(xnm: String, xqm: String): Map<String, String> = mapOf(
        "localeKey" to "zh_CN",
        "xnm" to xnm,
        "xqm" to xqm,
        "zs" to ""
    )

    /** Period start/end times. `sjkList` is empty on this deployment, so this endpoint supplies them. */
    val periodTimeCandidates: List<String> = listOf(
        "{base}/kbcx/xskbcx_cxRjc.html?gnmkdm=$GNMKDM_TIMETABLE",
        "{base}/xskbcx/xskbcx_cxRjc.html?gnmkdm=$GNMKDM_TIMETABLE"
    )

    /** Returns the current academic year/term without needing to scrape a page. */
    val currentSemesterCandidates: List<String> = listOf(
        "{base}/xtgl/index_cxCurrentSemester.html?gnmkdm=$GNMKDM_TIMETABLE"
    )

    /**
     * Candidate pages that render the timetable; used by WebView extraction and week detection.
     *
     * Order matters and is not the vendor default. Live capture (recon8) shows the first entry really
     * renders 学生课表查询: it contains the `*学年` / `*学期` selects, a `查询` button and the
     * `输出PDF / 表格 / 列表` toolbar. `xtgl/index_initMenu.html` is only the JS shell that hosts the
     * menu, so it carries no `select#xnm` and no timetable at all — reading it first is why semester
     * discovery always came back empty.
     */
    val timetablePageCandidates: List<String> = listOf(
        // The real 学生课表查询 page (has select#xnm / select#xqm, and the grid after 查询).
        "{base}/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=$GNMKDM_TIMETABLE",
        // The shell home page: still a useful "are we logged in?" probe, and on some deployments the
        // timetable module is embedded in it.
        "{base}/xtgl/index_initMenu.html",
        "{base}/xskbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=$GNMKDM_TIMETABLE"
    )

    /** A cheap protected endpoint used purely to answer "is the session still alive?". */
    val sessionProbeCandidates: List<String> = listOf(
        "{base}/framework/main.jsp",
        "{base}/xtgl/index_initMenu.html"
    )

    /** ZFSoft term codes. `xqm` is not 1/2/3 — the UI uses the encoded values below. */
    object Terms {
        const val FIRST = "3"
        const val SECOND = "12"
        const val THIRD = "16"

        fun all(): List<String> = listOf(FIRST, SECOND, THIRD)
    }

    fun apiUrl(baseUrl: String, template: String): String = template.replace("{base}", baseUrl.trimEnd('/'))

    /**
     * 把设置里那一个地址规范化成「应用根」。见 [SisEndpoints]：填站点根（`https://sis.slai.edu.cn`）
     * 和填应用地址（`…/yjsxt`）都得能拉课表，否则用户照着「登录要从根地址进」的说法一改设置，课表就没了。
     */
    fun baseUrlOrDefault(configured: String?): String = SisEndpoints.of(configured).base

    /**
     * 登录入口。**不是** `{origin}/yjsxt`：那条路径在未登录时 302 到正方自带的账号密码页，
     * 学生没有本地密码，也没有通往统一身份认证的链接，进去就是死胡同。
     */
    fun entryUrlOrDefault(configured: String?): String = SisEndpoints.of(configured).entry

    /** 已知 baseUrl 时推登录入口（静默续期用）。 */
    fun entryUrlFor(baseUrl: String): String = SisEndpoints.of(baseUrl).entry
}
