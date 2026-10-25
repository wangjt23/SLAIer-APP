package com.slai.campus.core.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class SemesterStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val anchor = LocalDate.of(2026, 9, 7)
    private val now = 1_800_000_000_000L
    private fun dataStore() = PreferenceDataStoreFactory.create(scope = scope) {
        directory.newFile("session.preferences_pb")
    }

    @After fun stopStore() { scope.cancel() }

    @Test fun `settings confirmation belongs to the configured year and term`() = runBlocking {
        val store = SessionStore(dataStore())
        store.setSemesterCodes("2026", "3")
        store.setSemesterAnchor(anchor, confirmed = true)
        assertThat(store.semesterConfiguration().anchorFor("2026", "3")).isEqualTo(anchor to true)
        assertThat(store.semesterConfiguration().anchorFor("2026", "12")).isEqualTo(null to false)
    }

    @Test fun `changing codes invalidates anchor confirmation and verification time`() = runBlocking {
        val store = SessionStore(dataStore())
        store.setSemesterConfiguration("2026", "3", anchor, true, now)
        store.setSemesterCodes("2026", "12")
        assertThat(store.semesterAnchor()).isEqualTo(null to false)
        assertThat(store.semesterConfiguration().verifiedAt).isEqualTo(0)
    }

    @Test fun `writing unchanged codes preserves the current anchor`() = runBlocking {
        val store = SessionStore(dataStore())
        store.setSemesterConfiguration("2026", "3", anchor, true, now)
        store.setSemesterCodes("2026", "3")
        assertThat(store.semesterAnchor()).isEqualTo(anchor to true)
        assertThat(store.semesterConfiguration().verifiedAt).isEqualTo(now)
    }

    @Test fun `observed semester configuration stays unconfirmed and bound to its term`() = runBlocking {
        val store = SessionStore(dataStore())
        store.setSemesterConfiguration("2026", "3", anchor, false, now)
        assertThat(store.semesterAnchor()).isEqualTo(anchor to false)
        assertThat(store.semesterConfiguration().canReuse(now + 1)).isTrue()
        assertThat(store.semesterConfiguration().anchorFor("2027", "3")).isEqualTo(null to false)
    }

    @Test fun `unverified new term cannot reuse previous term verification time`() = runBlocking {
        val store = SessionStore(dataStore())
        store.setSemesterConfiguration("2026", "3", anchor, false, now)
        store.setSemesterConfiguration("2026", "12", anchor.plusMonths(5), false)
        assertThat(store.semesterConfiguration().verifiedAt).isEqualTo(0)
        assertThat(store.semesterConfiguration().canReuse(now + 1)).isFalse()
    }

    @Test fun `legacy confirmation without semester ownership cannot skip discovery`() = runBlocking {
        val dataStore = dataStore()
        dataStore.edit {
            it[stringPreferencesKey("semester_year")] = "2026"
            it[stringPreferencesKey("semester_term")] = "3"
            it[longPreferencesKey("first_week_monday_epoch_day")] = anchor.toEpochDay()
            it[booleanPreferencesKey("first_week_anchor_confirmed")] = true
        }
        val store = SessionStore(dataStore)
        assertThat(store.semesterAnchor()).isEqualTo(null to false)
        assertThat(store.semesterConfiguration().canReuse(now)).isFalse()
    }

    @Test fun `initial user date is visible then binds atomically to first verified term`() = runBlocking {
        val store = SessionStore(dataStore())
        store.setSemesterAnchor(anchor, true)
        assertThat(store.semesterAnchor()).isEqualTo(anchor to true)
        store.setSemesterConfiguration("2026", "3", anchor, true, now)
        assertThat(store.semesterConfiguration().anchorFor("2026", "3")).isEqualTo(anchor to true)
        assertThat(store.semesterConfiguration().anchorFor("2026", "12")).isEqualTo(null to false)
    }
}
