package com.slai.campus.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultNetworkRevisionTest {
    @Test fun `registration does not report the already known network as a recovery`() {
        val tracker = DefaultNetworkRevision("mobile", 11)
        assertThat(tracker.available("mobile")).isNull()
        assertThat(tracker.capabilities("mobile", 11)).isNull()
    }

    @Test fun `switching mobile to wifi emits even if a default connection always exists`() {
        val tracker = DefaultNetworkRevision("mobile", 11)
        assertThat(tracker.available("wifi")).isEqualTo(1L)
        assertThat(tracker.capabilities("wifi", 7)).isEqualTo(2L)
        assertThat(tracker.lost("mobile")).isNull()
        assertThat(tracker.capabilities("wifi", 7)).isNull()
        assertThat(tracker.lost("wifi")).isEqualTo(3L)
        assertThat(tracker.available("mobile")).isEqualTo(4L)
    }

    @Test fun `validation recovery emits once and ignores duplicate capability callbacks`() {
        val tracker = DefaultNetworkRevision("wifi", 5)
        assertThat(tracker.capabilities("wifi", 7)).isEqualTo(1L)
        assertThat(tracker.capabilities("wifi", 7)).isNull()
        assertThat(tracker.capabilities("old-mobile", 11)).isNull()
    }

    @Test fun `offline recovery and later disconnect advance independent revisions`() {
        val tracker = DefaultNetworkRevision<String>()
        assertThat(tracker.available("wifi")).isEqualTo(1L)
        assertThat(tracker.lost("wifi")).isEqualTo(2L)
        assertThat(tracker.lost("wifi")).isNull()
        assertThat(tracker.available("wifi")).isEqualTo(3L)
    }
}
