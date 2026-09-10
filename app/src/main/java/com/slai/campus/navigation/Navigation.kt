package com.slai.campus.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.ui.graphics.vector.ImageVector
import com.slai.campus.core.common.SchoolSystem

/** Bottom-navigation destinations. Kept as a tiny enum: the app has four tabs and no deep links. */
enum class Tab(val labelRes: Int, val icon: ImageVector) {
    HOME(com.slai.campus.R.string.nav_home, Icons.Default.Home),
    SCHEDULE(com.slai.campus.R.string.nav_schedule, Icons.Default.CalendarMonth),
    ATTENDANCE(com.slai.campus.R.string.nav_attendance, Icons.Default.TaskAlt),
    SETTINGS(com.slai.campus.R.string.nav_settings, Icons.Default.Settings)
}

/**
 * Full-screen destinations layered on top of the tab content.
 *
 * A WebView must cover the whole screen (including the bottom bar), and the diagnostics console is a
 * developer tool that should not be a tab, so both are modelled as overlays rather than routes.
 */
sealed interface Overlay {
    data class Web(
        val url: String,
        val title: String,
        val system: SchoolSystem?,
        /** True when this WebView is completing a login and should trigger a probe + refresh. */
        val isLoginFlow: Boolean = false,
        /** True when this WebView is recording the page's own requests (endpoint learning). */
        val captureEnabled: Boolean = false,
        /**
         * 登录成功后是否自动关掉这个 WebView。
         *
         * 默认跟 [isLoginFlow] 走 —— 用户点的是"登录"，意图就是回来用 App，没必要让他自己去找左上角的 X。
         * 但**为了看网页**而打开的 WebView 必须显式传 false：那种页面即使顺路走了一遍 SSO，用户也是
         * 站在自己想看的页面上，把他踢回 App 是帮倒忙（见 CampusRoot 里那条"死胡同 → SSO"的兜底）。
         */
        val closeOnLogin: Boolean = isLoginFlow
    ) : Overlay

    data object Diagnostics : Overlay

    /** API provider management. */
    data object Providers : Overlay
}

/** Callbacks handed down to screens so they never construct navigation themselves. */
data class AppNavigator(
    val openTab: (Tab) -> Unit,
    val openWeb: (url: String, title: String, system: SchoolSystem?, isLoginFlow: Boolean) -> Unit,
    /** Opens the WebView in request-recording mode (endpoint learning). */
    val openCapture: (url: String, title: String, system: SchoolSystem?) -> Unit,
    val openDiagnostics: () -> Unit,
    val openProviders: () -> Unit,
    val closeOverlay: () -> Unit
)
