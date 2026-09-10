package com.slai.campus.data.stu

import com.slai.campus.BuildConfig
import com.slai.campus.core.common.SchoolSystem

/**
 * What is known about the student-affairs system (STU).
 *
 * Reconnaissance (unauthenticated, 2026-09-09) established:
 *  - `https://stu.slai.edu.cn/` answers `302 -> /sso/login -> 302 -> sts.slai.edu.cn/adfs/oauth2/authorize`
 *    with `client_id=623bdb15-1b88-44b7-ad20-5fbc6be52004`, `redirect_uri=…/sso/code`,
 *    `resource=https://stu.slai.edu.cn` — a second, independent AD FS OAuth client;
 *  - the application is **JeeSite** (`jeesite.session.id` cookie, `/a/<path>` protected URLs,
 *    unauthenticated access redirects to `/a/login;JSESSIONID=…`).
 *
 * **What is *not* known**, because it requires a real login: the check-in page path and whether a
 * stable read-only check-in endpoint exists. The plan is explicit that this must be verified against
 * the real system rather than guessed, so STU ships as [com.slai.campus.core.common.IntegrationMode.WEB_ONLY]
 * by default. The candidates below are only ever tried by the diagnostics probe, and a candidate is
 * promoted to a working integration only after it returns a payload that passes validation.
 */
object StuConfig {

    const val PARSER_VERSION = "stu-jeesite-1"

    val system = SchoolSystem.STU

    val defaultBaseUrl: String = BuildConfig.DEFAULT_STU_BASE_URL
    val defaultEntryUrl: String = BuildConfig.DEFAULT_STU_ENTRY_URL

    /** 学生考勤统计查询 —— 页面与数据接口（2026-09-09 实机抓包确认）。 */
    const val ATTENDANCE_PAGE = "{base}/a/edu/acm/swipe/attendList"
    const val ATTENDANCE_API = "{base}/a/edu/acm/swipe/weekGroupedByMonth"
    const val ATTENDANCE_WEEKS_API = "{base}/a/edu/acm/swipe/getWeeksInMonth"

    /** 学生刷卡记录表 —— 单个闸机进出记录（2026-09-09 实机抓包确认）。 */
    const val PUNCH_PAGE = "{base}/a/edu/acm/swipe/list"
    const val PUNCH_LIST_API = "{base}/a/edu/acm/swipe/listData"

    /** Marker of the JeeSite login page; also appears in the redirect target. */
    const val LOGIN_PATH = "/a/login"

    /** Protected page used as the session probe. */
    val sessionProbeCandidates: List<String> = listOf(
        "{base}/a/sys/user/info",
        "{base}/a/index",
        "{base}/a"
    )

    /**
     * Candidate check-in endpoints, tried only by the diagnostics probe and only in order.
     *
     * A candidate counts as verified when it answers 2xx with JSON or a page whose text contains one
     * of [CHECKED_IN_MARKERS] / [NOT_CHECKED_IN_MARKERS]. Until that happens the UI shows
     * "当前无法确认" — never "未打卡".
     */
    val checkInApiCandidates: List<String> = listOf(
        "{base}/a/checkin/today",
        "{base}/a/daka/today",
        "{base}/a/kaoqin/today",
        "{base}/a/attendance/today",
        "{base}/a/sys/checkin/status"
    )

    /** Candidate pages that render check-in state; used by WebView extraction. */
    val checkInPageCandidates: List<String> = listOf(
        "{base}/a/checkin",
        "{base}/a/daka",
        "{base}/a/kaoqin"
    )

    /**
     * Text that indicates a completed check-in. Matched case-insensitively against JSON string values
     * and rendered page text. Deliberately conservative: a false "已确认" is safer than a false
     * "未确认", but a false "未确认" is the one that must never happen, so ambiguous text yields
     * a distinct "unknown" outcome rather than a wrong answer.
     */
    val CHECKED_IN_MARKERS: List<String> = listOf(
        "已打卡", "已签到", "已确认", "已完成", "打卡成功", "签到成功", "checked_in", "checkedin",
        "\"checked\":true", "\"signed\":true", "\"status\":\"1\"", "\"status\":\"ok\""
    )

    val NOT_CHECKED_IN_MARKERS: List<String> = listOf(
        "未打卡", "未签到", "未确认", "未完成", "待打卡", "待签到", "\"checked\":false",
        "\"signed\":false", "\"status\":\"0\""
    )

    fun url(baseUrl: String, template: String): String = template.replace("{base}", baseUrl.trimEnd('/'))

    fun baseUrlOrDefault(configured: String?): String =
        configured?.takeIf { it.isNotBlank() } ?: defaultBaseUrl

    fun entryUrlOrDefault(configured: String?): String =
        configured?.takeIf { it.isNotBlank() } ?: defaultEntryUrl
}
