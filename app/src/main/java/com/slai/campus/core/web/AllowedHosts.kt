package com.slai.campus.core.web

import android.net.Uri
import com.slai.campus.core.common.SchoolSystem

/**
 * Host allow-list for the embedded WebView.
 *
 * Anything not in this set is handed to the system browser instead of being loaded inside the app.
 * This is the boundary that keeps the WebView from becoming a general-purpose browser with the
 * student's school session attached to it.
 */
object AllowedHosts {

    private val domains: Set<String> = setOf(
        "slai.edu.cn"
    )

    val hosts: Set<String> = setOf(
        "sis.slai.edu.cn",
        "stu.slai.edu.cn",
        "sts.slai.edu.cn",
        "www.slai.edu.cn"
    )

    /** True when [url] is https and its host is the school or a subdomain of it. */
    fun isAllowed(url: String?): Boolean {
        val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return hosts.contains(host) || domains.any { host == it || host.endsWith(".$it") }
    }

    /** True when the URL is plain http, which is always refused. */
    fun isCleartext(url: String?): Boolean =
        url?.trim()?.startsWith("http://", ignoreCase = true) == true

    fun hostOf(url: String?): String? = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }

    fun systemOf(url: String?): SchoolSystem? = when {
        url == null -> null
        url.contains("sis.slai.edu.cn") -> SchoolSystem.SIS
        url.contains("stu.slai.edu.cn") -> SchoolSystem.STU
        else -> null
    }
}

/**
 * Every screen reachable in the school's own web UI.
 *
 * The plan's rule: **原生功能坏掉 ≠ App 完全不可用**. Each of these is reachable from the corresponding
 * native screen, so a backend change degrades one feature instead of bricking the app.
 */
sealed class SchoolPage {

    abstract val url: String
    abstract val system: SchoolSystem
    abstract val label: String

    data class SisHome(override val url: String) : SchoolPage() {
        override val system: SchoolSystem = SchoolSystem.SIS
        override val label: String = "教务系统首页"
    }

    data class SisSchedule(override val url: String) : SchoolPage() {
        override val system: SchoolSystem = SchoolSystem.SIS
        override val label: String = "教务课表"
    }

    data class SisCourseSelection(override val url: String) : SchoolPage() {
        override val system: SchoolSystem = SchoolSystem.SIS
        override val label: String = "选课"
    }

    data class SisExam(override val url: String) : SchoolPage() {
        override val system: SchoolSystem = SchoolSystem.SIS
        override val label: String = "考试安排"
    }

    data class SisGrades(override val url: String) : SchoolPage() {
        override val system: SchoolSystem = SchoolSystem.SIS
        override val label: String = "成绩查询"
    }

    data class StuHome(override val url: String) : SchoolPage() {
        override val system: SchoolSystem = SchoolSystem.STU
        override val label: String = "学生系统首页"
    }

    data class StuCheckIn(override val url: String) : SchoolPage() {
        override val system: SchoolSystem = SchoolSystem.STU
        override val label: String = "打卡"
    }

    companion object {
        fun sisHome(baseUrl: String) = SisHome("${baseUrl.trimEnd('/')}/xtgl/index_initMenu.html")
        fun sisSchedule(baseUrl: String) =
            SisSchedule("${baseUrl.trimEnd('/')}/xskbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N253508")
        fun sisCourseSelection(baseUrl: String) =
            SisCourseSelection("${baseUrl.trimEnd('/')}/xsxk/zzxkyzb_cxZzxkYzbIndex.html")
        fun sisExam(baseUrl: String) = SisExam("${baseUrl.trimEnd('/')}/kwgl/ksxx_cxKsxxIndex.html")
        fun sisGrades(baseUrl: String) = SisGrades("${baseUrl.trimEnd('/')}/cjcx/cjcx_cxDgXscjIndex.html")
        fun stuHome(baseUrl: String) = StuHome("${baseUrl.trimEnd('/')}/a/index")
        fun stuCheckIn(baseUrl: String) = StuCheckIn("${baseUrl.trimEnd('/')}/a/checkin")
    }
}
