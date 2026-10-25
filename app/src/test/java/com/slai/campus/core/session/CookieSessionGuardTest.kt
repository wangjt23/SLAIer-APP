package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CookieSessionGuardTest {
    @Test fun `clearing one system revokes its browsers before cleanup without closing sibling`() {
        val guard = CookieSessionGuard()
        val closed = mutableListOf<String>()
        val sis = guard.beginBrowser("sis.slai.edu.cn") { closed += "sis" }
        val stu = guard.beginBrowser("stu.slai.edu.cn") { closed += "stu" }
        guard.invalidate("sis.slai.edu.cn")
        assertThat(closed).containsExactly("sis")
        // STU is still browsing, so native writes remain protected.
        assertThat(guard.claimWrite(guard.snapshot("stu.slai.edu.cn", null), null)).isFalse()
        guard.endBrowser(sis)
        guard.endBrowser(stu)
        assertThat(guard.claimWrite(guard.snapshot("stu.slai.edu.cn", null), null)).isTrue()
        assertThat(closed).containsExactly("sis")
    }

    @Test fun `all target callbacks run even if one browser cleanup throws`() {
        val guard = CookieSessionGuard()
        var closed = false
        guard.beginBrowser("stu.slai.edu.cn") { throw IllegalStateException("fixture") }
        guard.beginBrowser("stu.slai.edu.cn") { closed = true }
        guard.invalidate("stu.slai.edu.cn")
        assertThat(closed).isTrue()
        assertThat(guard.claimWrite(guard.snapshot("stu.slai.edu.cn", null), null)).isTrue()
    }

    @Test fun `redirect flow permit survives own host writes but not explicit clearing`() {
        val guard = CookieSessionGuard()
        val first = guard.snapshot("stu.slai.edu.cn", "old")
        assertThat(guard.claimWrite(first, "old")).isTrue()
        assertThat(first.writePermitted()).isTrue()
        val second = guard.snapshot("stu.slai.edu.cn", "new").withWritePermit(first::writePermitted)
        assertThat(guard.claimWrite(second, "new")).isTrue()
        guard.invalidate("stu.slai.edu.cn")
        val late = guard.snapshot("stu.slai.edu.cn", null).withWritePermit(second::writePermitted)
        assertThat(guard.claimWrite(late, null)).isFalse()
    }

    @Test fun `revoked request permit refuses a fresh snapshot at actual cookie commit`() {
        val guard = CookieSessionGuard()
        var permitted = true
        val fresh = guard.snapshot("stu.slai.edu.cn", "session=fresh").withWritePermit { permitted }
        permitted = false
        assertThat(guard.claimWrite(fresh, "session=fresh")).isFalse()
        assertThat(guard.claimWrite(guard.snapshot("stu.slai.edu.cn", "session=fresh"), "session=fresh")).isTrue()
    }

    @Test fun `new host starts at long zero and can accept its first cookie`() {
        val guard = CookieSessionGuard()
        val first = guard.snapshot("stu.slai.edu.cn", null)
        assertThat(first.version).isEqualTo(0L)
        assertThat(first.hostVersion).isEqualTo(0L)
        assertThat(guard.claimWrite(first, null)).isTrue()
        assertThat(guard.snapshot("stu.slai.edu.cn", "session=new").hostVersion).isEqualTo(1L)
        assertThat(guard.snapshot("sis.slai.edu.cn", null).hostVersion).isEqualTo(0L)
    }

    @Test fun `browser cycle invalidates old request even if cookie value returns to previous value`() {
        val guard = CookieSessionGuard()
        val old = guard.snapshot("stu.slai.edu.cn", "session=same")
        val browser = guard.beginBrowser()
        guard.endBrowser(browser)
        assertThat(guard.claimWrite(old, "session=same")).isFalse()
        assertThat(guard.claimWrite(guard.snapshot("stu.slai.edu.cn", "session=same"), "session=same")).isTrue()
    }

    @Test fun `native response cannot write until every browser is closed`() {
        val guard = CookieSessionGuard()
        val first = guard.beginBrowser()
        val second = guard.beginBrowser()
        val during = guard.snapshot("stu.slai.edu.cn", "session=old")
        guard.endBrowser(first)
        assertThat(guard.claimWrite(guard.snapshot("stu.slai.edu.cn", "session=old"), "session=old")).isFalse()
        guard.endBrowser(second)
        assertThat(guard.claimWrite(during, "session=old")).isFalse()
        assertThat(guard.claimWrite(guard.snapshot("stu.slai.edu.cn", "session=old"), "session=old")).isTrue()
    }

    @Test fun `only one concurrent response can replace a cookie generation`() {
        val guard = CookieSessionGuard()
        val first = guard.snapshot("stu.slai.edu.cn", "session=old")
        val late = guard.snapshot("stu.slai.edu.cn", "session=old")
        assertThat(guard.claimWrite(first, "session=old")).isTrue()
        assertThat(guard.claimWrite(late, "session=old")).isFalse()
        assertThat(guard.claimWrite(guard.snapshot("stu.slai.edu.cn", "session=new"), "session=new")).isTrue()
    }

    @Test fun `cookie updates outside bridge and explicit sign out invalidate response`() {
        val guard = CookieSessionGuard()
        val old = guard.snapshot("stu.slai.edu.cn", "session=old")
        assertThat(guard.claimWrite(old, "session=new")).isFalse()
        guard.invalidate()
        assertThat(guard.claimWrite(old, "session=old")).isFalse()
    }

    @Test fun `independent systems can renew concurrently but old same-host response is rejected`() {
        val guard = CookieSessionGuard()
        val sis = guard.snapshot("sis.slai.edu.cn", "JSESSIONID=old")
        val stu = guard.snapshot("stu.slai.edu.cn", "session=old")
        val oldSis = guard.snapshot("sis.slai.edu.cn", "JSESSIONID=old")
        assertThat(guard.claimWrite(stu, "session=old")).isTrue()
        assertThat(guard.claimWrite(sis, "JSESSIONID=old")).isTrue()
        // Even equal cookie values cannot allow a response from an older generation of this host.
        assertThat(guard.claimWrite(oldSis, "JSESSIONID=old")).isFalse()
    }

    @Test fun `browser navigation and explicit clearing invalidate every system request`() {
        val guard = CookieSessionGuard()
        val sis = guard.snapshot("sis.slai.edu.cn", "JSESSIONID=old")
        val stu = guard.snapshot("stu.slai.edu.cn", "session=old")
        val browser = guard.beginBrowser()
        guard.endBrowser(browser)
        assertThat(guard.claimWrite(sis, "JSESSIONID=old")).isFalse()
        assertThat(guard.claimWrite(stu, "session=old")).isFalse()
        val afterBrowser = guard.snapshot("sis.slai.edu.cn", "JSESSIONID=new")
        guard.invalidate()
        assertThat(guard.claimWrite(afterBrowser, "JSESSIONID=new")).isFalse()
    }
}
