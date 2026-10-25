package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HostCookieCleanupTest {
    @Test fun `cleanup reads path-scoped cookies and targets only that host domain variants`() {
        val reads = mutableListOf<String>()
        val paths = HostCookieCleanup.paths(listOf("https://stu.slai.edu.cn/custom/session/api"))
        val removals = HostCookieCleanup.removals("stu.slai.edu.cn", paths) { url ->
            reads += url
            when {
                url.contains("/a/edu/") -> "jeesite.session.id=secret"
                url.contains("/custom/session") -> "custom=secret"
                else -> null
            }
        }
        assertThat(reads).contains("https://stu.slai.edu.cn/a/edu/acm/swipe/listData")
        assertThat(removals.map { it.value }).contains("jeesite.session.id=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/a; Secure; HttpOnly")
        assertThat(removals.any { it.value.startsWith("custom=") && it.value.contains("Path=/custom/session;") }).isTrue()
        assertThat(removals.any { it.value.contains("Domain=slai.edu.cn") }).isFalse()
        assertThat(removals.any { it.value.contains("Domain=stu.slai.edu.cn") }).isTrue()
        assertThat(removals.all { it.url.startsWith("https://stu.slai.edu.cn/") }).isTrue()
    }

    @Test fun `host prefix removals preserve required secure host-only root attributes`() {
        val removals = HostCookieCleanup.removals("stu.slai.edu.cn", HostCookieCleanup.paths(emptyList())) {
            "__Host-session=secret; __Secure-session=secret"
        }
        val host = removals.filter { it.value.startsWith("__Host-") }
        assertThat(host).hasSize(1)
        assertThat(host.single().value).contains("Path=/; Secure; HttpOnly")
        assertThat(host.single().value).doesNotContain("Domain=")
        assertThat(removals.filter { it.value.startsWith("__Secure-") }.all { it.value.contains("Secure") }).isTrue()
    }

    @Test fun `cleanup refuses a host containing path or port and never targets sibling host`() {
        assertThat(HostCookieCleanup.removals("stu.slai.edu.cn/evil", setOf("/")) { "session=secret" }).isEmpty()
        assertThat(HostCookieCleanup.removals("stu.slai.edu.cn:443", setOf("/")) { "session=secret" }).isEmpty()
        val removals = HostCookieCleanup.removals("sis.slai.edu.cn", setOf("/", "/yjsxt")) { "session=secret" }
        assertThat(removals.any { it.url.contains("stu.slai.edu.cn") || it.value.contains("Domain=edu.cn") }).isFalse()
    }
}
