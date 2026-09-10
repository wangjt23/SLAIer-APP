package com.slai.campus.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/**
 * 进程级 CoroutineScope。
 *
 * 只给"派生流的生命周期 = 进程生命周期"的单例用（目前是 `SessionManager` 的会话状态：
 * 它必须一直热着，否则 `stateOf()` 这类同步读会拿到陈旧的初值）。
 * **不要**拿它跑业务请求 —— 那些应该挂在 ViewModel 或 WorkManager 的作用域上。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Dispatcher holder so that repositories can be tested on a `TestDispatcher` without touching
 * `Dispatchers` singletons.
 */
data class AppDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main.immediate
)
