package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class SemesterConfigurationTest {
    private val now = 1_800_000_000_000L
    private val anchor = LocalDate.of(2026, 9, 7)
    private val current = SemesterConfiguration("2026", "3", anchor, true, "2026", "3", now)

    @Test fun `same term preserves user confirmation`() {
        assertThat(current.anchorFor("2026", "3")).isEqualTo(anchor to true)
    }

    @Test fun `new term cannot inherit previous anchor or confirmation`() {
        assertThat(current.anchorFor("2026", "12")).isEqualTo(null to false)
        assertThat(current.anchorFor("2027", "3")).isEqualTo(null to false)
    }

    @Test fun `server observation remains unconfirmed`() {
        assertThat(current.copy(userConfirmed = false).anchorFor("2026", "3")).isEqualTo(anchor to false)
        assertThat(current.copy(userConfirmed = false).canReuse(now + 1000)).isTrue()
    }

    @Test fun `term is verified daily and after clock goes backwards`() {
        assertThat(current.canReuse(now + SemesterConfiguration.VERIFICATION_INTERVAL_MS - 1)).isTrue()
        assertThat(current.canReuse(now + SemesterConfiguration.VERIFICATION_INTERVAL_MS)).isFalse()
        assertThat(current.canReuse(now - 1)).isFalse()
    }

    @Test fun `legacy unbound anchor and guessed term require discovery`() {
        val legacy = current.copy(anchorYear = null, anchorTerm = null, verifiedAt = 0)
        assertThat(legacy.anchorFor("2026", "3")).isEqualTo(null to false)
        assertThat(legacy.canReuse(now)).isFalse()
        assertThat(current.copy(verifiedAt = 0).canReuse(now)).isFalse()
    }

    @Test fun `user date set before first ever login can bind to first discovered term`() {
        val initial = current.copy(year = null, term = null, anchorYear = null, anchorTerm = null, verifiedAt = 0)
        assertThat(initial.anchorFor("2026", "3")).isEqualTo(anchor to true)
        assertThat(initial.canReuse(now)).isFalse()
    }
}
