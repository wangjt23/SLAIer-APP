package com.slai.campus.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 浅色：学院官方模板的配色 —— 深洋红主色 + 青蓝次色，底色是极浅的暖灰。
 */
private val LightColors = lightColorScheme(
    primary = BrandPalette.Magenta600,
    onPrimary = Color.White,
    primaryContainer = BrandPalette.Magenta50,
    onPrimaryContainer = BrandPalette.Magenta900,

    secondary = BrandPalette.Cyan600,
    onSecondary = Color.White,
    secondaryContainer = BrandPalette.Cyan50,
    onSecondaryContainer = Color(0xFF00344A),

    tertiary = BrandPalette.Magenta300,
    onTertiary = Color.White,
    tertiaryContainer = BrandPalette.Magenta50,
    onTertiaryContainer = BrandPalette.Magenta900,

    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    background = Color(0xFFFDF7FA),
    onBackground = BrandPalette.Ink,
    surface = Color(0xFFFDF7FA),
    onSurface = BrandPalette.Ink,
    surfaceVariant = BrandPalette.Grey100,
    onSurfaceVariant = BrandPalette.Grey600,
    surfaceContainer = Color(0xFFF7EDF3),
    surfaceContainerHigh = Color(0xFFF2E6ED),
    surfaceContainerLow = Color(0xFFFBF3F7),

    outline = BrandPalette.Grey400,
    outlineVariant = BrandPalette.Grey200,
    inverseSurface = BrandPalette.Ink800,
    inverseOnSurface = BrandPalette.Grey50
)

/**
 * 深色：底色带一点勃艮第而非纯灰，和品牌色同族；主色提亮到 `#E27DBE` 以保证对比度。
 */
private val DarkColors = darkColorScheme(
    primary = BrandPalette.Magenta100,
    onPrimary = BrandPalette.Magenta900,
    primaryContainer = BrandPalette.Magenta700,
    onPrimaryContainer = BrandPalette.Magenta50,

    secondary = BrandPalette.Cyan200,
    onSecondary = Color(0xFF00344A),
    secondaryContainer = Color(0xFF0B5878),
    onSecondaryContainer = BrandPalette.Cyan50,

    tertiary = BrandPalette.Magenta100,
    onTertiary = BrandPalette.Magenta900,
    tertiaryContainer = BrandPalette.Magenta800,
    onTertiaryContainer = BrandPalette.Magenta50,

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = BrandPalette.SurfaceDark,
    onBackground = Color(0xFFEDE0E6),
    surface = BrandPalette.SurfaceDark,
    onSurface = Color(0xFFEDE0E6),
    surfaceVariant = BrandPalette.SurfaceContainerHighDark,
    onSurfaceVariant = Color(0xFFCFBFC7),
    surfaceContainer = BrandPalette.SurfaceContainerDark,
    surfaceContainerHigh = BrandPalette.SurfaceContainerHighDark,
    surfaceContainerLow = Color(0xFF201820),

    outline = Color(0xFF8C7B84),
    outlineVariant = Color(0xFF43353C),
    inverseSurface = Color(0xFFEDE0E6),
    inverseOnSurface = Color(0xFF322A2E)
)

/**
 * 当前生效的是不是深色。
 *
 * 给那些"要按底色挑素材"的地方用，目前是 [com.slai.campus.ui.components.SlaiLogo]
 * （黑字 / 白字两份 logo）。
 *
 * 为什么不直接调 `isSystemInDarkTheme()`：用户可以在设置里**强制**浅色或深色。
 * 系统是深色、应用被强制成浅色时，`isSystemInDarkTheme()` 仍然返回 true，
 * 就会在浅色背景上放一份白字 logo —— 直接看不见。
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * 品牌主题。
 *
 * **刻意不再使用 Material You 动态取色。** 之前 `Build.VERSION.SDK_INT >= S` 时会走
 * `dynamicDarkColorScheme`，结果是手机上取到什么色，App 就是什么色 —— 学院那套洋红/青蓝
 * 完全显示不出来，官方模板的视觉识别也就无从谈起。现在固定使用品牌色。
 *
 * [darkTheme] 由调用方决定（用户设置 + 系统状态合成），默认仍然跟随系统。
 */
@Composable
fun SlaiCampusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = SlaiTypography,
            content = content
        )
    }
}
