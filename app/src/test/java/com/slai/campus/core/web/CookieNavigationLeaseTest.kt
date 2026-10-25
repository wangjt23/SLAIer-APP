package com.slai.campus.core.web

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CookieNavigationLeaseTest {
    @Test fun `abandoned composition releases browser protection without disposable effect`() {
        var browsers = 0
        val lease = CookieNavigationLease {
            browsers++
            AutoCloseable { browsers-- }
        }
        assertThat(browsers).isEqualTo(1)
        lease.onAbandoned()
        assertThat(browsers).isEqualTo(0)
        lease.close()
        lease.onForgotten()
        assertThat(browsers).isEqualTo(0)
    }

    @Test fun `remembered composition and disposable cleanup release lease exactly once`() {
        var closed = 0
        val lease = CookieNavigationLease { AutoCloseable { closed++ } }
        lease.onRemembered()
        assertThat(closed).isEqualTo(0)
        lease.close()
        lease.onForgotten()
        lease.onAbandoned()
        assertThat(closed).isEqualTo(1)
    }
}
