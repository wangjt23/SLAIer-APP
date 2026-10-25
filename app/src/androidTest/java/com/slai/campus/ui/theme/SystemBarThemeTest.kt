package com.slai.campus.ui.theme

import android.os.Build
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slai.campus.ui.ScrollChromeTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemBarThemeTest {
    @Test fun explicitAppThemeUpdatesBothSystemBarsWithoutRecreatingActivity() {
        ActivityScenario.launch(ScrollChromeTestActivity::class.java).use { scenario ->
            var originalChildCount = 0
            scenario.onActivity { activity ->
                activity.initializeSystemBars()
                originalChildCount = (activity.window.decorView as ViewGroup).childCount
            }
            for (darkTheme in listOf(true, false, true, false)) {
                scenario.onActivity { activity ->
                    activity.applySystemBarTheme(darkTheme)
                    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    assertEquals("Status-bar icons must follow the app theme", !darkTheme,
                        controller.isAppearanceLightStatusBars)
                    assertEquals("Navigation-bar icons must follow the app theme", !darkTheme,
                        controller.isAppearanceLightNavigationBars)
                    assertEquals("Theme changes must not append system protection views", originalChildCount,
                        (activity.window.decorView as ViewGroup).childCount)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        assertTrue("Three-button navigation must retain automatic contrast protection",
                            activity.window.isNavigationBarContrastEnforced)
                    }
                }
            }
        }
    }
}
