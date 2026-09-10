package com.slai.campus.core.common

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
}
