package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cache partitioning: switching accounts must not surface the previous account's timetable, and the
 * stored key must not be reversible back to a student number.
 */
class AccountHasherTest {

    @Test
    fun `hash is stable for the same id`() {
        assertThat(AccountHasher.hash("20260001")).isEqualTo(AccountHasher.hash("20260001"))
    }

    @Test
    fun `hash differs between accounts`() {
        assertThat(AccountHasher.hash("20260001")).isNotEqualTo(AccountHasher.hash("20260002"))
    }

    @Test
    fun `hash is case and whitespace insensitive`() {
        assertThat(AccountHasher.hash("  20260001 ")).isEqualTo(AccountHasher.hash("20260001"))
        assertThat(AccountHasher.hash("AbC")).isEqualTo(AccountHasher.hash("abc"))
    }

    @Test
    fun `hash does not contain the plaintext id`() {
        val hash = AccountHasher.hash("20260001")
        assertThat(hash).doesNotContain("20260001")
        assertThat(hash).hasLength(32)
    }

    @Test
    fun `device local hash is random when no hint is given`() {
        assertThat(AccountHasher.deviceLocalHash(null))
            .isNotEqualTo(AccountHasher.deviceLocalHash(null))
    }

    @Test
    fun `device local hash follows the hint when present`() {
        assertThat(AccountHasher.deviceLocalHash("20260001"))
            .isEqualTo(AccountHasher.hash("20260001"))
    }

    @Test
    fun `display form is short and non-reversible`() {
        val display = AccountHasher.display(AccountHasher.hash("20260001"))
        assertThat(display).hasLength(9) // 8 chars + ellipsis
        assertThat(AccountHasher.display(null)).isEqualTo("—")
    }
}
