package com.slai.campus.core.common

import java.util.Locale

/**
 * 界面语言。
 *
 * 三种取值而不是一个布尔：**默认必须跟随系统**。手机是英文的留学生打开 App 就该是英文，
 * 不该逼他先找到设置里的开关 —— 那是最容易被忽略的一步。
 *
 * [tag] 是 BCP-47 语言标签，直接喂给 `Locale.forLanguageTag`。
 */
enum class AppLanguage(val tag: String, val storedValue: String) {
    SYSTEM("", "system"),
    CHINESE("zh-CN", "zh"),
    ENGLISH("en", "en");

    /** 实际生效的 Locale。SYSTEM 交给系统，返回 null。 */
    val localeOrNull: Locale?
        get() = when (this) {
            SYSTEM -> null
            else -> Locale.forLanguageTag(tag)
        }

    companion object {
        fun fromStored(value: String?): AppLanguage =
            entries.firstOrNull { it.storedValue == value } ?: SYSTEM

        /**
         * 系统语言是不是中文。用于没有任何覆盖时的默认判断，
         * 以及非 Compose 场景（通知、Worker）里挑选文案。
         */
        fun systemPrefersChinese(): Boolean =
            Locale.getDefault().language.startsWith("zh")
    }
}
