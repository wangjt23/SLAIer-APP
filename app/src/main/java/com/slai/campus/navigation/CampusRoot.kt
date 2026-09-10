package com.slai.campus.navigation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slai.campus.R
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.web.SchoolWebScreen
import com.slai.campus.feature.attendance.AttendanceScreen
import com.slai.campus.feature.diagnostics.DiagnosticsScreen
import com.slai.campus.feature.home.HomeScreen
import com.slai.campus.feature.provider.ProviderScreen
import com.slai.campus.feature.schedule.WeekScheduleScreen
import com.slai.campus.feature.settings.SettingsScreen

/**
 * The whole navigation surface.
 *
 * Four tabs plus three full-screen overlays (WebView, diagnostics, API providers). No navigation
 * library: the app has no deep links, no argument passing beyond a URL, and back behaviour is exactly
 * "close the overlay, otherwise leave the tab", which is easier to get right by hand than with a graph.
 */
@Composable
fun CampusRoot(viewModel: MainViewModel = hiltViewModel()) {
    var selectedTab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }

    val session by viewModel.session.collectAsStateWithLifecycle()
    val webCompleting by viewModel.webCompleting.collectAsStateWithLifecycle()
    val sisEntryUrl by viewModel.sisEntryUrl.collectAsStateWithLifecycle()
    val sisEndpoints by viewModel.sisEndpoints.collectAsStateWithLifecycle()
    val sisLoginLabel = stringResource(R.string.schedule_sign_in)
    val loginAutoCloseHint = stringResource(R.string.web_login_autoclose_hint)
    val context = LocalContext.current

    /*
     * 登录确认成功之后，WebView 自己退场。
     *
     * 只关"用户为了登录而打开"的那种（closeOnLogin）：点登录的人意图就是回来用 App，
     * 让他自己去左上角找 X 是多余的一步。而"打开教务系统看网页"那种即使顺路登录成功也**不关** ——
     * 他正站在自己想看的页面上。
     */
    LaunchedEffect(Unit) {
        viewModel.loginCompleted.collect {
            if ((overlay as? Overlay.Web)?.closeOnLogin != true) return@collect
            // 让"登录成功"那一帧在屏幕上停一下再退场：页面瞬间消失会像闪退。
            kotlinx.coroutines.delay(LOGIN_AUTO_CLOSE_DELAY_MS)
            // 这期间用户可能自己关了、或者换了别的页面，那就不必再关一次。
            if ((overlay as? Overlay.Web)?.closeOnLogin != true) return@collect
            overlay = null
            // 页面凭空消失如果不给一句解释，会像是崩了。首页那条"正在同步"要滚到下面才看得见，
            // 而从设置页/课表页登录时根本不在首页。
            Toast.makeText(context, R.string.home_login_syncing, Toast.LENGTH_SHORT).show()
        }
    }

    val navigator = remember(selectedTab, sisEntryUrl) {
        AppNavigator(
            openTab = { selectedTab = it },
            openWeb = { url, title, system, isLoginFlow ->
                overlay = Overlay.Web(url, title, system, isLoginFlow, captureEnabled = false)
            },
            openCapture = { url, title, system ->
                overlay = Overlay.Web(url, title, system, isLoginFlow = false, captureEnabled = true)
            },
            openDiagnostics = { overlay = Overlay.Diagnostics },
            openProviders = { overlay = Overlay.Providers },
            closeOverlay = { overlay = null }
        )
    }

    BackHandler(enabled = overlay != null) { overlay = null }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                /*
                 * 默认的 Material 3 导航栏用 `secondaryContainer` 当选中指示器；这里显式指定，
                 * 让选中项落在品牌主色系上，而不是系统取色出来的浅蓝。
                 */
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (selectedTab) {
                    Tab.HOME -> HomeScreen(
                        navigator = navigator,
                        session = session,
                        webCompleting = webCompleting
                    )
                    Tab.SCHEDULE -> WeekScheduleScreen(navigator = navigator)
                    Tab.ATTENDANCE -> AttendanceScreen(navigator = navigator)
                    Tab.SETTINGS -> SettingsScreen(navigator = navigator, session = session)
                }
            }
        }

        when (val current = overlay) {
            null -> Unit

            /*
             * key(url)：WebView 的 loadUrl 只在 factory 里跑一次，换 URL 而不换实例的话新地址根本不会
             * 加载 —— 下面那条「死胡同 → SSO 入口」的兜底正是靠换 overlay 实现的，所以这里必须让
             * 新地址拿到一个新的 WebView（旧实例在 onDispose 里销毁，Cookie 在 CookieManager 里，不受影响）。
             */
            is Overlay.Web -> key(current.url) {
                SchoolWebScreen(
                    initialUrl = current.url,
                    title = current.title,
                    onClose = { overlay = null },
                    captureEnabled = current.captureEnabled,
                    // 登录页上先讲清楚"成功就会自动回去"，别等用户输完密码再提示关闭。
                    hint = if (current.closeOnLogin) loginAutoCloseHint else null,
                    onFinishCapture = { session ->
                        viewModel.onCaptureFinished(session)
                        overlay = Overlay.Providers
                    },
                    onPageFinished = { url ->
                        viewModel.onWebPageFinished(url, current.isLoginFlow)
                        /*
                         * 未登录时打开任何业务页面（“打开教务系统”、课表网页兜底……），学校会 302 到正方
                         * 自带的 `/yjsxt/xtgl/login_slogin.html`：一个学生没有密码、也没有任何通往统一
                         * 身份认证链接的表单，进去就是死胡同。
                         *
                         * 与其把人丢在那儿，不如直接换成真正的 SSO 入口重走一遍。新 overlay 带
                         * isLoginFlow=true，所以登录成功后照样会探活并刷新课表，而且不会来回跳
                         * ——兜底只对“非登录流程”的页面生效。
                         */
                        val rescued = when {
                            current.isLoginFlow || current.captureEnabled -> null
                            current.system != SchoolSystem.SIS -> null
                            else -> sisEndpoints.ssoFallbackFor(url)
                        }
                        if (rescued != null) {
                            overlay = Overlay.Web(
                                url = rescued,
                                title = sisLoginLabel,
                                system = SchoolSystem.SIS,
                                isLoginFlow = true,
                                // 用户本来是想看网页的，登录只是路过 —— 成功了也别把他踢回 App。
                                closeOnLogin = false
                            )
                        }
                    }
                )
            }

            Overlay.Diagnostics -> DiagnosticsScreen(
                onClose = { overlay = null },
                onOpenProviders = navigator.openProviders,
                onOpenCapture = { navigator.openCapture(sisEntryUrl, "抓取课表请求", null) }
            )

            Overlay.Providers -> ProviderScreen(
                navigator = navigator,
                onClose = { overlay = null },
                sisEntryUrl = sisEntryUrl
            )
        }
    }
}

/** 登录成功后让 WebView 多停一会儿再退场，给用户看清"确实登录上了"。 */
private const val LOGIN_AUTO_CLOSE_DELAY_MS = 600L
