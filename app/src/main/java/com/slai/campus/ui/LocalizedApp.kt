package com.slai.campus.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.LayoutDirection
import com.slai.campus.core.common.AppLanguage
import java.util.Locale

/**
 * 让整个 Compose 树按 [language] 取字符串资源。
 *
 * 为什么不用 `AppCompatDelegate.setApplicationLocales`：那套是给 `AppCompatActivity` 用的，
 * 本应用是 `ComponentActivity` + 纯 Compose；在 API 33 以下它依赖 AppCompat 的
 * `attachBaseContext` 钩子，对普通 Activity 并不可靠，而且会重建 Activity（切换语言时闪一下、
 * 丢掉当前页面状态）。这里改成给子树换一个带 Locale 的 `Context`，
 * 切换是**即时**的，不需要重建，也不需要多引一个 appcompat 依赖。
 *
 * 三个 Compose local 都要换，缺一不可：
 *  - `LocalResources`   —— **实际生效的那一个**：Compose 1.9.2 的 `stringResource`
 *                          反编译后就是读 `LocalResources.current`（5 处引用），
 *                          只换 `LocalContext` 是不起作用的。
 *  - `LocalContext`     —— 给 `painterResource` 和其它直接拿 Context 的地方。
 *  - `LocalConfiguration` —— 供 `LocalConfiguration.current.locales` 与日期格式化读取。
 *
 * **`LocalContext` 必须换成 [LocalizedContextWrapper]，不能直接塞
 * `createConfigurationContext()` 的返回值。** 后者是 `ContextImpl`，`baseContext` 为空，
 * 而 Hilt 的 `hiltViewModel()` 靠 `ContextWrapper` 链往上找 `ComponentActivity` 来建
 * ViewModelFactory，找不到就抛：
 *
 * ```text
 * IllegalStateException: Expected an activity context for creating a HiltViewModelFactory
 * but instead found: android.app.ContextImpl
 * ```
 *
 * 这个崩溃只在"切语言之后第一次解析 ViewModel"时出现，所以在模拟器上点一下才暴露出来。
 */
@Composable
fun LocalizedApp(
    language: AppLanguage,
    systemLanguage: String,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    val targetTag = language.tag.ifBlank { systemLanguage }
    val needsOverride = targetTag.isNotBlank() &&
        configuration.locales[0]?.toLanguageTag() != targetTag

    if (!needsOverride) {
        content()
        return
    }

    val overrideConfiguration = remember(targetTag, configuration) {
        Configuration(configuration).apply { setLocale(Locale.forLanguageTag(targetTag)) }
    }
    val localizedContext = remember(targetTag, context) {
        LocalizedContextWrapper(context, overrideConfiguration)
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalResources provides localizedContext.resources,
        LocalConfiguration provides overrideConfiguration,
        LocalLayoutDirection provides
            if (overrideConfiguration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            },
        content = content
    )
}

/**
 * 一个"语言被换掉"的 Context，但仍然是 `ContextWrapper`。
 *
 * 关键在于 [baseContext] 保持原样（Activity）：`Context.findActivity()` 那类
 * "沿着 ContextWrapper 链往上找" 的逻辑照样能找到 Activity —— 这正是 Hilt 需要的。
 * 只覆盖资源相关的四个方法，其余全部委托。
 */
private class LocalizedContextWrapper(
    base: Context,
    overrideConfiguration: Configuration
) : ContextWrapper(base) {

    private val localized: Context = base.createConfigurationContext(overrideConfiguration)

    override fun getResources(): Resources = localized.resources

    override fun getAssets(): AssetManager = localized.assets

    override fun getTheme(): Resources.Theme = localized.theme

    override fun createConfigurationContext(config: Configuration): Context =
        localized.createConfigurationContext(config)
}

/** 当前生效的 Locale，给日期/时长格式化用。 */
@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/**
 * Date pattern per language.
 *
 * The app used to hard-code `"M月d日 EEEE"` with `Locale.CHINA` everywhere, which renders
 * "9月10日 星期四" even in English. Patterns have to differ per language — there is no single
 * pattern that reads correctly in both.
 */
fun datePatternFor(locale: Locale, withWeekday: Boolean = true): String = when {
    locale.language.startsWith("zh") -> if (withWeekday) "M月d日 EEEE" else "yyyy年M月d日"
    else -> if (withWeekday) "EEEE, MMM d" else "MMM d, yyyy"
}

/** Short weekday + date, for the compact day headers. */
fun shortDatePatternFor(locale: Locale): String =
    if (locale.language.startsWith("zh")) "M月d日 EEE" else "EEE, MMM d"
