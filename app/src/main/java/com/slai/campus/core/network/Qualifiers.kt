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
