package com.slai.campus.core.common

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class DateRolloverTest {
    @Test fun `date subscription advances after local midnight without restarting app`() = runTest {
        var now = Instant.parse("2026-09-13T15:59:59Z")
        val zone = ZoneId.of("Asia/Shanghai")
        val clock = object : Clock() {
            override fun instant() = now
            override fun getZone() = zone
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        }
        val dates = mutableListOf<LocalDate>()
        backgroundScope.launch { TimeProvider(clock).observeDate().collect { dates += it } }
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertThat(dates).containsExactly(LocalDate.parse("2026-09-13"))
        now = now.plusSeconds(61)
        advanceTimeBy(30_000); runCurrent()
        assertThat(dates).containsExactly(LocalDate.parse("2026-09-13"), LocalDate.parse("2026-09-14")).inOrder()
    }
}
