package com.slai.campus.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedLoginStatusTest {
    @Test
    fun `cooldown uses minutes and zero padded seconds`() {
        assertEquals("5:00", formatSavedLoginRetryTime(300))
        assertEquals("4:59", formatSavedLoginRetryTime(299))
        assertEquals("1:05", formatSavedLoginRetryTime(65))
        assertEquals("0:09", formatSavedLoginRetryTime(9))
    }

    @Test
    fun `expired cooldown never displays a negative time`() {
        assertEquals("0:00", formatSavedLoginRetryTime(0))
        assertEquals("0:00", formatSavedLoginRetryTime(-1))
    }
}
