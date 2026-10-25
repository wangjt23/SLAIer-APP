package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticLoginWakeupTest {
    private val enabled = SavedLoginStatus(saved = true, enabled = true)

    @Test fun `foreground network listening survives disabled paused and removed passwords`() = runTest {
        val foreground = MutableStateFlow(false)
        val status = MutableStateFlow(enabled)
        var registrations = 0
        var unregistrations = 0
        val network = flow<Long> {
            registrations++
            try { awaitCancellation() } finally { unregistrations++ }
        }
        val job = backgroundScope.launch {
            automaticLoginWakeups(foreground, status, network) { testScheduler.currentTime }.collect()
        }
        runCurrent()
        assertThat(registrations).isEqualTo(0)
        foreground.value = true
        runCurrent()
        assertThat(registrations).isEqualTo(1)
        status.value = enabled.copy(paused = true)
        runCurrent()
        assertThat(unregistrations).isEqualTo(0)
        status.value = enabled.copy(enabled = false)
        runCurrent()
        status.value = enabled.copy(saved = false)
        runCurrent()
        assertThat(registrations).isEqualTo(1)
        status.value = enabled
        runCurrent()
        assertThat(registrations).isEqualTo(1)
        foreground.value = false
        runCurrent()
        assertThat(unregistrations).isEqualTo(1)
        job.cancel()
    }

    @Test fun `restored cooldown wakes once at its remaining deadline without polling`() = runTest {
        val foreground = MutableStateFlow(true)
        val status = MutableStateFlow(enabled.copy(retryAfter = 300_000))
        val events = mutableListOf<AutomaticLoginWakeup>()
        backgroundScope.launch {
            automaticLoginWakeups(foreground, status, MutableSharedFlow()) {
                testScheduler.currentTime + 120_000
            }.collect { events += it }
        }
        runCurrent()
        advanceTimeBy(179_999)
        runCurrent()
        assertThat(events).isEmpty()
        advanceTimeBy(1)
        runCurrent()
        assertThat(events).containsExactly(AutomaticLoginWakeup.COOLDOWN_FINISHED)
        foreground.value = false
        runCurrent()
        foreground.value = true
        runCurrent()
        advanceTimeBy(86_400_000)
        runCurrent()
        assertThat(events).containsExactly(AutomaticLoginWakeup.COOLDOWN_FINISHED)
    }

    @Test fun `interrupted foreground wait is cancelled then resumed at original deadline`() = runTest {
        val foreground = MutableStateFlow(true)
        val status = MutableStateFlow(enabled.copy(retryAfter = 300_000))
        val events = mutableListOf<AutomaticLoginWakeup>()
        backgroundScope.launch {
            automaticLoginWakeups(foreground, status, MutableSharedFlow()) {
                testScheduler.currentTime
            }.collect { events += it }
        }
        runCurrent()
        advanceTimeBy(100_000)
        foreground.value = false
        runCurrent()
        advanceTimeBy(250_000)
        runCurrent()
        assertThat(events).isEmpty()
        foreground.value = true
        runCurrent()
        assertThat(events).containsExactly(AutomaticLoginWakeup.COOLDOWN_FINISHED)
    }

    @Test fun `new credential submission cancels the previous cooldown deadline`() = runTest {
        val status = MutableStateFlow(enabled.copy(retryAfter = 100_000))
        val events = mutableListOf<AutomaticLoginWakeup>()
        backgroundScope.launch {
            automaticLoginWakeups(MutableStateFlow(true), status, MutableSharedFlow()) {
                testScheduler.currentTime
            }.collect { events += it }
        }
        runCurrent()
        advanceTimeBy(50_000)
        status.value = enabled.copy(retryAfter = 350_000)
        runCurrent()
        advanceTimeBy(50_000)
        runCurrent()
        assertThat(events).isEmpty()
        advanceTimeBy(250_000)
        runCurrent()
        assertThat(events).containsExactly(AutomaticLoginWakeup.COOLDOWN_FINISHED)
    }

    @Test fun `network change wakes a probe while credential cooldown remains in force`() = runTest {
        val foreground = MutableStateFlow(true)
        val status = MutableStateFlow(enabled.copy(retryAfter = 300_000))
        val network = MutableSharedFlow<Long>(extraBufferCapacity = 1)
        val events = mutableListOf<AutomaticLoginWakeup>()
        backgroundScope.launch {
            automaticLoginWakeups(foreground, status, network) {
                testScheduler.currentTime
            }.collect { events += it }
        }
        runCurrent()
        network.emit(1)
        runCurrent()
        assertThat(events).containsExactly(AutomaticLoginWakeup.NETWORK_CHANGED)
        assertThat(status.value.canAttemptAt(testScheduler.currentTime)).isFalse()
        foreground.value = false
        runCurrent()
        network.emit(2)
        runCurrent()
        assertThat(events).hasSize(1)
    }

    @Test fun `verification challenge pause cancels password cooldown but permits cookie recovery`() = runTest {
        val status = MutableStateFlow(enabled.copy(retryAfter = 300_000))
        val network = MutableSharedFlow<Long>()
        val events = mutableListOf<AutomaticLoginWakeup>()
        backgroundScope.launch {
            automaticLoginWakeups(MutableStateFlow(true), status, network) {
                testScheduler.currentTime
            }.collect { events += it }
        }
        runCurrent()
        status.value = status.value.copy(paused = true)
        runCurrent()
        network.emit(1)
        advanceTimeBy(600_000)
        runCurrent()
        assertThat(events).containsExactly(AutomaticLoginWakeup.NETWORK_CHANGED)
    }

    @Test fun `network can wake cookie recovery with no saved password or a disabled switch`() = runTest {
        val status = MutableStateFlow(SavedLoginStatus())
        val network = MutableSharedFlow<Long>()
        val events = mutableListOf<AutomaticLoginWakeup>()
        backgroundScope.launch {
            automaticLoginWakeups(MutableStateFlow(true), status, network) {
                testScheduler.currentTime
            }.collect { events += it }
        }
        runCurrent()
        network.emit(1)
        runCurrent()
        status.value = enabled.copy(enabled = false, retryAfter = 100_000)
        runCurrent()
        network.emit(2)
        runCurrent()
        advanceTimeBy(200_000)
        runCurrent()
        assertThat(events).containsExactly(
            AutomaticLoginWakeup.NETWORK_CHANGED, AutomaticLoginWakeup.NETWORK_CHANGED
        ).inOrder()
    }

    @Test fun `disabled and removed password settings cancel only the pending cooldown`() = runTest {
        for (unavailable in listOf(
            enabled.copy(enabled = false, retryAfter = 100_000),
            enabled.copy(saved = false, retryAfter = 100_000)
        )) {
            val status = MutableStateFlow(enabled.copy(retryAfter = testScheduler.currentTime + 100_000))
            val network = MutableSharedFlow<Long>()
            val events = mutableListOf<AutomaticLoginWakeup>()
            val job = backgroundScope.launch {
                automaticLoginWakeups(MutableStateFlow(true), status, network) {
                    testScheduler.currentTime
                }.collect { events += it }
            }
            runCurrent()
            status.value = unavailable
            runCurrent()
            network.emit(1)
            runCurrent()
            advanceTimeBy(200_000)
            runCurrent()
            assertThat(events).containsExactly(AutomaticLoginWakeup.NETWORK_CHANGED)
            job.cancel()
            runCurrent()
        }
    }

    @Test fun `clearing credential cooldown after successful sign in cancels pending expiry`() = runTest {
        val status = MutableStateFlow(enabled.copy(retryAfter = 300_000))
        val events = mutableListOf<AutomaticLoginWakeup>()
        backgroundScope.launch {
            automaticLoginWakeups(MutableStateFlow(true), status, MutableSharedFlow()) {
                testScheduler.currentTime
            }.collect { events += it }
        }
        runCurrent()
        status.value = enabled
        runCurrent()
        advanceTimeBy(600_000)
        runCurrent()
        assertThat(events).isEmpty()
    }

    @Test fun `display countdown rounds up partial seconds and reaches zero at saved deadline`() = runTest {
        val seconds = mutableListOf<Int>()
        backgroundScope.launch {
            savedLoginRetrySeconds(MutableStateFlow(enabled.copy(retryAfter = 1_501))) {
                testScheduler.currentTime
            }.collect { seconds += it }
        }
        runCurrent()
        assertThat(seconds).containsExactly(2)
        advanceTimeBy(500)
        runCurrent()
        assertThat(seconds).containsExactly(2)
        advanceTimeBy(1)
        runCurrent()
        assertThat(seconds).containsExactly(2, 1).inOrder()
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(seconds).containsExactly(2, 1, 0).inOrder()
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(seconds).hasSize(3)
    }

    @Test fun `display countdown immediately clears for paused disabled or removed credentials`() = runTest {
        val status = MutableStateFlow(enabled.copy(retryAfter = 100_000))
        val seconds = mutableListOf<Int>()
        backgroundScope.launch {
            savedLoginRetrySeconds(status) { testScheduler.currentTime }.collect { seconds += it }
        }
        runCurrent()
        assertThat(seconds.last()).isEqualTo(100)
        for (unavailable in listOf(
            status.value.copy(paused = true), status.value.copy(enabled = false),
            status.value.copy(saved = false), enabled.copy(retryAfter = -1)
        )) {
            status.value = unavailable
            runCurrent()
            assertThat(seconds.last()).isEqualTo(0)
            status.value = enabled.copy(retryAfter = 100_000)
            runCurrent()
        }
    }

    @Test fun `display countdown replaces a previous saved retry deadline`() = runTest {
        val status = MutableStateFlow(enabled.copy(retryAfter = 10_000))
        val seconds = mutableListOf<Int>()
        backgroundScope.launch {
            savedLoginRetrySeconds(status) { testScheduler.currentTime }.collect { seconds += it }
        }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        status.value = enabled.copy(retryAfter = 300_000)
        runCurrent()
        assertThat(seconds).containsExactly(10, 9, 299).inOrder()
    }
}
