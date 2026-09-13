package com.slai.campus.core.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of "now" so that date-sensitive logic (today's classes, week numbers, reminder
 * scheduling) is deterministic in tests.
 */
@Singleton
class TimeProvider @Inject constructor(
    private val clock: Clock
) {
    val zone: ZoneId get() = clock.zone

    fun now(): Instant = clock.instant()

    fun nowDateTime(): LocalDateTime = LocalDateTime.now(clock)

    fun today(): LocalDate = LocalDate.now(clock)

    fun nowLocalTime(): LocalTime = LocalTime.now(clock)

    fun epochMillis(): Long = clock.millis()

    /** Emits on collection and when the local date changes; does not access the network. */
    fun observeDate(): Flow<LocalDate> = flow {
        while (true) {
            emit(today())
            delay(30_000)
        }
    }.distinctUntilChanged()
}
