package com.slai.campus.core.network

import javax.inject.Qualifier

/**
 * Type-safe qualifiers for the HTTP clients.
 *
 * Each school system gets its own pair: a *web* client that follows redirects (loading pages) and
 * an *api* client that does not (so a `302` to the login page stays visible and can be classified as
 * an expired session instead of being silently followed).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SisApiClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SisWebClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StuApiClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StuWebClient

/**
 * 给 GitHub（应用内更新）用的客户端。
 *
 * 刻意不挂 [WebViewCookieInterceptor]：往 GitHub 发请求绝不能带上教务/学工系统的会话 cookie。
 * 超时也比学校那套宽 —— 要流式下载几 MB 的 APK，18 秒的总超时会直接掐断。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdateClient
