package com.slai.campus.core.session

/** Never a data class: Cookie values must not appear in generated toString/logs. */
internal class CookieRequestSnapshot(
    internal val host: String,
    val header: String?,
    internal val version: Long,
    internal val hostVersion: Long,
    internal val writable: Boolean,
    private val canWrite: () -> Boolean = { true }
) {
    fun withWritePermit(permit: () -> Boolean): CookieRequestSnapshot = CookieRequestSnapshot(
        host, header, version, hostVersion, writable, { canWrite() && permit() }
    )

    internal fun writePermitted(): Boolean = canWrite()
}

/** Main-thread-owned generation counter shared by browser loads and native Cookie writes. */
internal class CookieSessionGuard {
    private var version = 0L
    private var nextBrowser = 0L
    private class Browser(val host: String?, val onInvalidated: () -> Unit)
    private val browsers = mutableMapOf<Long, Browser>()
    private val hostVersions = mutableMapOf<String, Long>()

    fun snapshot(host: String, header: String?): CookieRequestSnapshot {
        val generation = version
        return CookieRequestSnapshot(host, header, generation, hostVersions[host] ?: 0L,
            browsers.isEmpty(), { version == generation })
    }

    fun beginBrowser(host: String? = null, onInvalidated: () -> Unit = {}): Long {
        version++
        return (++nextBrowser).also { browsers[it] = Browser(host, onInvalidated) }
    }

    fun endBrowser(browser: Long) {
        if (browsers.remove(browser) != null) version++
    }

    /** Revoke the target's browser loads before CookieManager clears that system's cookies. */
    fun invalidate(host: String? = null) {
        version++
        val revoked = browsers.filterValues { host == null || it.host == null || it.host == host }
        revoked.keys.forEach(browsers::remove)
        revoked.values.forEach { runCatching(it.onInvalidated) }
    }

    /** Claim before writing so another response sent with the same old cookies cannot follow it. */
    fun claimWrite(snapshot: CookieRequestSnapshot, currentHeader: String?): Boolean {
        if (!snapshot.writePermitted() || !snapshot.writable || browsers.isNotEmpty() || snapshot.version != version ||
            snapshot.hostVersion != (hostVersions[snapshot.host] ?: 0L) ||
            snapshot.header != currentHeader) return false
        // SIS and STU may renew at the same time. A normal write in one system must not discard
        // the other system's response; browser navigation and explicit clearing remain global.
        hostVersions[snapshot.host] = snapshot.hostVersion + 1
        return true
    }
}
