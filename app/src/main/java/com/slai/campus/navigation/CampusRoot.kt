package com.slai.campus.navigation

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

            is Overlay.Web -> SchoolWebScreen(
                initialUrl = current.url,
                title = current.title,
                onClose = { overlay = null },
                captureEnabled = current.captureEnabled,
                onFinishCapture = { session ->
                    viewModel.onCaptureFinished(session)
                    overlay = Overlay.Providers
                },
                onPageFinished = { url ->
                    viewModel.onWebPageFinished(url, current.isLoginFlow)
                }
            )

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
