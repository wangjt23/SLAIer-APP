package com.slai.campus

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.slai.campus.core.common.AppLanguage
import com.slai.campus.core.common.AppTheme
import com.slai.campus.core.session.SessionStore
import com.slai.campus.navigation.CampusRoot
import com.slai.campus.ui.LocalizedApp
import com.slai.campus.ui.theme.SlaiCampusTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var sessionStore: SessionStore

    /**
     * Android 13+ notification permission.
     *
     * Requested once at startup rather than when a reminder is first enabled, so the prompt is not
     * tied to a background moment. A denial is respected: reminders simply do not post, and the
     * settings screen explains how to grant it later.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Result is intentionally not persisted: the settings screen re-checks the real state.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()

        setContent {
            /*
             * 语言在主题**外面**包一层：`stringResource` 读的是 CompositionLocal，
             * 与 MaterialTheme 无关；放外层可以保证 WebView、诊断、通知之外的每个界面都跟着切。
             *
             * 默认是"跟随系统"（AppLanguage.SYSTEM），所以英文手机的留学生第一次打开就是英文，
             * 不需要先去设置里找开关。
             */
            val language by sessionStore.appLanguage.collectAsState(initial = AppLanguage.SYSTEM)
            val theme by sessionStore.appTheme.collectAsState(initial = AppTheme.SYSTEM)

            LocalizedApp(
                language = language,
                systemLanguage = Locale.getDefault().toLanguageTag()
            ) {
                // 用户设置 + 系统状态合成出最终结果：SYSTEM 时跟随系统，
                // 其余两种直接覆盖。写成热流，切换是即时的，不重建 Activity。
                SlaiCampusTheme(darkTheme = theme.isDark(isSystemInDarkTheme())) {
                    CampusRoot()
                }
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
