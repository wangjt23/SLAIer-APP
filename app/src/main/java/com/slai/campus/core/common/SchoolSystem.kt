package com.slai.campus.core.common

/**
 * Which school system a piece of data came from. Used for cache isolation, sync metadata and the
 * "open in the original web page" fallback, so a SIS outage never affects STU and vice versa.
 */
enum class SchoolSystem(val key: String, val displayName: String, val host: String) {
    SIS("sis", "教务系统", "sis.slai.edu.cn"),
    STU("stu", "学生系统", "stu.slai.edu.cn");

    companion object {
        fun fromKey(key: String): SchoolSystem? = entries.firstOrNull { it.key == key }
    }
}

/**
 * How a given system is integrated on this device. Recorded per system because the two systems are
 * validated independently: SIS may be fully native while STU stays web-only, which is an acceptable
 * shipping state.
 */
enum class IntegrationMode {
    /** Native HTTP request reusing the WebView session; preferred. */
    NATIVE_API,

    /** Data read out of a page the WebView itself loaded (page's own XHR or rendered DOM). */
    WEBVIEW_EXTRACT,

    /** Only the original web page is offered. Always available as a fallback. */
    WEB_ONLY
}
