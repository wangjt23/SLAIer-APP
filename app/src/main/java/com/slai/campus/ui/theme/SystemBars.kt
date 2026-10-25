package com.slai.campus.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat

/** Configure edge-to-edge once, keeping automatic contrast protection for three-button navigation. */
fun ComponentActivity.initializeSystemBars() {
    val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}

/** Update only the icon appearance when the app overrides or switches its theme. */
fun ComponentActivity.applySystemBarTheme(darkTheme: Boolean) {
    // Repeating enableEdgeToEdge would append another ProtectionLayout on Android 15+. Insets,
    // transparent backgrounds and navigation contrast protection stay as configured at startup.
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !darkTheme
        isAppearanceLightNavigationBars = !darkTheme
    }
}
