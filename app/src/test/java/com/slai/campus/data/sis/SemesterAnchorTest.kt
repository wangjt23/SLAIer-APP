package com.slai.campus.data.sis

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class SemesterAnchorTest {
    private val stored = LocalDate.of(2026, 8, 31)
    private val observed = LocalDate.of(2026, 9, 7)
    private val page = LocalDate.of(2026, 9, 14)

    @Test fun `confirmed stored anchor takes priority over server and page`() {
        assertThat(resolve(stored, true, observed, page))
            .isEqualTo(SemesterAnchor(stored, "stored+confirmed"))
    }

    @Test fun `server teaching week corrects an unconfirmed stored anchor`() {
        assertThat(resolve(stored, false, observed, page))
            .isEqualTo(SemesterAnchor(observed, "server week"))
    }

    @Test fun `page anchor takes priority over unconfirmed storage`() {
        assertThat(resolve(stored, false, null, page))
            .isEqualTo(SemesterAnchor(page, "page select"))
    }

    @Test fun `stored anchor remains available when no current evidence exists`() {
        assertThat(resolve(stored, false, null, null))
            .isEqualTo(SemesterAnchor(stored, "stored"))
    }

    @Test fun `confirmation without a stored date does not hide server evidence`() {
        assertThat(resolve(null, true, observed, page))
            .isEqualTo(SemesterAnchor(observed, "server week"))
    }

    @Test fun `missing evidence estimates from the term or stays unanchored`() {
        assertThat(resolve(null, false, null, null))
            .isEqualTo(SemesterAnchor(LocalDate.of(2026, 9, 7), "estimate"))
        assertThat(resolveSemesterAnchor(null, false, null, null, null, null).monday).isNull()
    }

    private fun resolve(
        storedAnchor: LocalDate?,
        confirmed: Boolean,
        observedAnchor: LocalDate?,
        pageAnchor: LocalDate?
    ) = resolveSemesterAnchor(storedAnchor, confirmed, observedAnchor, pageAnchor, "2026", "3")
}
