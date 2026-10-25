package com.slai.campus.navigation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.slai.campus.ui.components.AutomaticLoginNotice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
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
import com.slai.campus.ui.components.LocalScrollChromeEnabled
import com.slai.campus.ui.components.LocalScrollChromeMotion
import com.slai.campus.ui.components.rememberScrollChromeMotionState
import com.slai.campus.ui.components.ScrollingNavigationBar
import com.slai.campus.ui.components.rememberTouchExplorationEnabled

/**
 * The whole navigation surface.
 *
 * Four tabs plus three full-screen overlays (WebView, diagnostics, API providers). No navigation
 * library: the app has no deep links, no argument passing beyond a URL, and back behaviour is exactly
 * "close the overlay, otherwise leave the tab", which is easier to get right by hand than with a graph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusRoot(viewModel: MainViewModel = hiltViewModel()) {
    var selectedTab by rememberSaveable { mutableStateOf(Tab.HOME) }
    /*
     * overlay 也必须 rememberSaveable：只用一个 remember 的话，进程被系统回收后重建会出现
     * "tab 还在、浮层没了"的半状态。恢复策略（哪些该恢复、哪些刻意不恢复）在 OverlayPersistence。
     */
    var overlay by rememberSaveable(stateSaver = OverlaySaver) { mutableStateOf<Overlay?>(null) }
    val touchExplorationEnabled = rememberTouchExplorationEnabled()
    // Short destinations keep navigation immediately available; their content can still scroll.
    val chromeScrollEnabled = selectedTab.hidesChromeOnScroll && overlay == null && !touchExplorationEnabled
    val chromeMotion = key(selectedTab) { rememberScrollChromeMotionState() }
    LaunchedEffect(chromeMotion, overlay, touchExplorationEnabled) {
        if (!chromeScrollEnabled) chromeMotion.reset()
    }

    val session by viewModel.session.collectAsStateWithLifecycle()
    val savedLogin by viewModel.savedLogin.collectAsStateWithLifecycle()
    val automaticLoginState by viewModel.automaticLoginState.collectAsStateWithLifecycle()
    var resumed by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose {
            viewModel.stopAutomaticLogin()
            resumed = false
        }
    }
    val webCompleting by viewModel.webCompleting.collectAsStateWithLifecycle()
    val sisEntryUrl by viewModel.sisEntryUrl.collectAsStateWithLifecycle()
    val sisEndpoints by viewModel.sisEndpoints.collectAsStateWithLifecycle()
    val sisLoginLabel = stringResource(R.string.schedule_sign_in)
    val loginAutoCloseHint = stringResource(R.string.web_login_autoclose_hint)
    val context = LocalContext.current

    LaunchedEffect(session.stu, savedLogin, overlay, resumed) {
        viewModel.updateAutomaticLogin(foregroundAvailable = resumed && overlay == null)
    }
    LaunchedEffect(selectedTab) {
        if (selectedTab == Tab.ATTENDANCE) viewModel.requestAttendanceRecovery()
    }
    LaunchedEffect(overlay) {
        viewModel.updateWebLoginFlow((overlay as? Overlay.Web)?.takeIf { it.isLoginFlow }?.flowId)
    }

    /*
     * 登录确认成功之后，WebView 自己退场。
     *
     * 只关"用户为了登录而打开"的那种（closeOnLogin）：点登录的人意图就是回来用 App，
     * 让他自己去左上角找 X 是多余的一步。而"打开教务系统看网页"那种即使顺路登录成功也**不关** ——
     * 他正站在自己想看的页面上。
     */
    LaunchedEffect(Unit) {
        viewModel.loginCompleted.collect { flowId ->
            if (!closeCompletedLoginOverlay(flowId, { overlay }, { overlay = null })) return@collect
            // 页面凭空消失如果不给一句解释，会像是崩了。首页那条"正在同步"要滚到下面才看得见，
            // 而从设置页/课表页登录时根本不在首页。
            Toast.makeText(context, R.string.home_login_syncing, Toast.LENGTH_SHORT).show()
        }
    }

    val navigator = remember(selectedTab, sisEntryUrl) {
        AppNavigator(
            openTab = { selectedTab = it },
            openWeb = { url, title, system, isLoginFlow ->
                viewModel.stopAutomaticLogin()
                val page = Overlay.Web(url, title, system, isLoginFlow, captureEnabled = false)
                if (isLoginFlow) viewModel.beginManualLogin(system, page.flowId)
                overlay = page
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

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(
            LocalScrollChromeEnabled provides chromeScrollEnabled,
            LocalScrollChromeMotion provides chromeMotion
        ) {
            // System areas stay fixed. The lists fill this viewport while controls translate over
            // them; hiding navigation never changes the content constraints or reserves a blank row.
            Box(Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).clipToBounds()) {
                when (selectedTab) {
                    Tab.HOME -> HomeScreen(navigator = navigator)
                    Tab.SCHEDULE -> WeekScheduleScreen(navigator = navigator)
                    Tab.ATTENDANCE -> AttendanceScreen(navigator = navigator)
                    Tab.SETTINGS -> SettingsScreen(navigator = navigator, session = session)
                }
                ScrollingNavigationBar(
                    state = chromeMotion,
                    modifier = Modifier.align(Alignment.BottomCenter)
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
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().graphicsLayer {
                    translationY = -chromeMotion.bottomBarHeightPx * (1f - chromeMotion.hiddenFraction)
                }) {
                    AutomaticLoginNotice(automaticLoginState)
                }
            }
            // The Android gesture/three-button area is a system inset, separate from app controls.
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars)
                .background(MaterialTheme.colorScheme.surfaceContainer))
        }

        when (val current = overlay) {
            null -> Unit

            /*
             * 每次打开页面都有独立 flowId：即使重新打开同一 URL，也需要新的 WebView 和登录确认。
             * 旧实例在 onDispose 中销毁，Cookie 保存在 CookieManager 中。
             */
            is Overlay.Web -> key(current.flowId) {
                SchoolWebScreen(
                    initialUrl = current.url,
                    title = current.title,
                    beginCookieNavigation = { onInvalidated ->
                        viewModel.beginSchoolCookieNavigation(current.system, onInvalidated)
                    },
                    onClose = { overlay = null },
                    captureEnabled = current.captureEnabled,
                    // 登录页上先讲清楚"成功就会自动回去"，别等用户输完密码再提示关闭。
                    hint = if (current.closeOnLogin) loginAutoCloseHint else null,
                    onFinishCapture = { session ->
                        viewModel.onCaptureFinished(session)
                        overlay = Overlay.Providers
                    },
                    onPageFinished = { url ->
                        viewModel.onWebPageFinished(url, current.isLoginFlow, current.flowId)
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
                            val page = Overlay.Web(
                                url = rescued,
                                title = sisLoginLabel,
                                system = SchoolSystem.SIS,
                                isLoginFlow = true,
                                // 用户本来是想看网页的，登录只是路过 —— 成功了也别把他踢回 App。
                                closeOnLogin = false
                            )
                            viewModel.beginManualLogin(SchoolSystem.SIS, page.flowId)
                            overlay = page
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

/** 把 [OverlayPersistence] 的纯编解码接到 Compose 的保存/恢复机制上。 */
private val OverlaySaver = Saver<Overlay?, List<String>>(
    save = { overlay -> OverlayPersistence.encode(overlay) },
    restore = { saved -> OverlayPersistence.decode(saved) }
)
