package com.slai.campus.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 语言与主题这两个偏好。
 *
 * 两者形状一样（三态、默认跟随系统、存的是字符串），而且都有同一个容易写错的地方：
 * **默认值必须是"跟随系统"**，不能硬编码成中文或浅色 ——
 * 英文手机的用户第一次打开就该是英文，开了深色的手机就该是深色。
 */
class AppPreferencesTest {

    // -----------------------------------------------------------------------------------
    // 语言
    // -----------------------------------------------------------------------------------

    @Test
    fun `language maps to a locale and defaults to following the system`() {
        assertThat(AppLanguage.SYSTEM.localeOrNull).isNull()   // 交给系统，不能写死
        assertThat(AppLanguage.CHINESE.localeOrNull?.language).isEqualTo("zh")
        assertThat(AppLanguage.ENGLISH.localeOrNull?.language).isEqualTo("en")
    }

    @Test
    fun `language survives a storage round trip and falls back safely`() {
        AppLanguage.entries.forEach { language ->
            assertThat(AppLanguage.fromStored(language.storedValue)).isEqualTo(language)
        }
        assertThat(AppLanguage.fromStored(null)).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.fromStored("")).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.fromStored("klingon")).isEqualTo(AppLanguage.SYSTEM)
    }

    // -----------------------------------------------------------------------------------
    // 主题
    // -----------------------------------------------------------------------------------

    @Test
    fun `SYSTEM theme follows whatever the system reports`() {
        assertThat(AppTheme.SYSTEM.isDark(systemInDark = true)).isTrue()
        assertThat(AppTheme.SYSTEM.isDark(systemInDark = false)).isFalse()
    }

    @Test
    fun `an explicit theme overrides the system in both directions`() {
        // 这两种情况才是这个功能存在的理由：系统是深色但用户想要浅色，反之亦然。
        assertThat(AppTheme.LIGHT.isDark(systemInDark = true)).isFalse()
        assertThat(AppTheme.DARK.isDark(systemInDark = false)).isTrue()
    }

    @Test
    fun `theme survives a storage round trip and falls back safely`() {
        AppTheme.entries.forEach { theme ->
            assertThat(AppTheme.fromStored(theme.storedValue)).isEqualTo(theme)
        }
        assertThat(AppTheme.fromStored(null)).isEqualTo(AppTheme.SYSTEM)
        assertThat(AppTheme.fromStored("sepia")).isEqualTo(AppTheme.SYSTEM)
    }

    @Test
    fun `the default for both preferences is to follow the system`() {
        assertThat(AppLanguage.fromStored(null)).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppTheme.fromStored(null)).isEqualTo(AppTheme.SYSTEM)
    }
}
