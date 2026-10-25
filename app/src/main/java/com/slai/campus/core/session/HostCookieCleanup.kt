package com.slai.campus.core.session

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class CookieRemoval(val url: String, val value: String)

/** CookieManager exposes values per URL, but not a complete cookie inventory or cookie paths. */
internal object HostCookieCleanup {
    private val protectedPaths = listOf(
        "/", "/yjsxt", "/yjsxt/htxylogin", "/yjsxt/framework/main.jsp",
        "/yjsxt/xtgl/index_initMenu.html", "/yjsxt/kbcx/xskbcx_cxXsKb.html",
        "/yjsxt/xskbcx/xskbcx_cxXsKb.html", "/sso/login", "/sso/code",
        "/a/index", "/a/login", "/a/sys/user/info", "/a/edu/acm/swipe/listData",
        "/a/edu/acm/swipe/weekGroupedByMonth", "/adfs/ls", "/adfs/oauth2/authorize"
    )

    fun paths(observedUrls: Collection<String>): Set<String> = buildSet {
        (protectedPaths + observedUrls.mapNotNull { it.toHttpUrlOrNull()?.encodedPath }).forEach { path ->
            add("/")
            // Both trailing-slash and slashless scopes are distinct cookie paths.
            path.split('/').filter { it.isNotEmpty() }.runningFold("") { prefix, segment -> "$prefix/$segment" }
                .filter { it.isNotEmpty() }.forEach { add(it); add("$it/") }
        }
    }

    fun removals(host: String, paths: Set<String>, readCookie: (String) -> String?): List<CookieRemoval> {
        val origin = "https://$host"
        val parsed = origin.toHttpUrlOrNull() ?: return emptyList()
        if (parsed.host != host || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return emptyList()
        // Read every protected scope before deleting: reading only '/' misses names scoped to /a etc.
        val names = paths.flatMap { path -> readCookie("$origin$path").orEmpty().split(';') }
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() && !it.any { c -> c <= ' ' || c in ";=," } }.toSet()
        val domains = buildList<String?> {
            add(null) // host-only
            add(host)
            // A parent-domain Cookie also authenticates sibling systems and possibly AD FS.
            // This action signs out one system; never delete shared parent-domain credentials.
        }
        return names.flatMap { name ->
            val cookiePaths = if (name.startsWith("__Host-")) setOf("/") else paths
            val cookieDomains = if (name.startsWith("__Host-")) listOf(null) else domains
            cookiePaths.flatMap { path -> cookieDomains.map { domain ->
                CookieRemoval("$origin$path", buildString {
                    append("$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path; Secure; HttpOnly")
                    if (domain != null) append("; Domain=$domain")
                })
            } }
        }
    }
}
