package com.slai.campus.core.common

/**
 * 界面深浅色。
 *
 * 和 [AppLanguage] 一样是三态，默认**跟随系统** —— 手机开了深色就该是深色，
 * 不该逼用户进设置里再选一次。
 */
enum class AppTheme(val storedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    /** 结合系统当前状态，算出这次到底用不用深色。 */
    fun isDark(systemInDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStored(value: String?): AppTheme =
            entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}
