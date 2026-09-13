package com.slai.campus.feature.home

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.session.SessionState
import com.slai.campus.domain.schedule.SyncState
import com.slai.campus.feature.schedule.WeekUiState
import org.junit.Test
import java.time.Instant

class CachedTimetableTest {
    private val cache = SyncState(SchoolSystem.SIS, lastSuccessAt = Instant.parse("2026-09-01T00:00:00Z"))
    @Test fun `long-lived cache with no class today is not a first-run login state`() {
        val state = HomeUiState(syncState = cache)
        assertThat(state.hasData).isTrue()
        assertThat(state.isFirstRun).isFalse()
    }
    @Test fun `a week with no classes still has valid cached timetable after session expires`() {
        val state = WeekUiState(syncState = cache, sisState = SessionState.EXPIRED)
        assertThat(state.hasAnyData).isFalse()
        assertThat(state.hasCache).isTrue()
    }
}
