package com.slai.campus.data.provider

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.slai.campus.domain.provider.ApiProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercise the actual DataStore transaction, rather than a separately tested list helper. */
class ProviderStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun store() = ProviderStore(PreferenceDataStoreFactory.create(scope = scope) {
        directory.newFile("providers.preferences_pb")
    })

    @After fun stopStore() { scope.cancel() }

    @Test fun `concurrent additions preserve every provider`() = runBlocking<Unit> {
        val store = store()
        val providers = (1..50).map { provider("$it") }
        together(providers.map { provider -> suspend { store.upsert(provider) } })
        assertThat(store.all()).containsExactlyElementsIn(providers)
    }

    @Test fun `upsert deletion and enabling preserve unrelated simultaneous edits`() = runBlocking<Unit> {
        val store = store()
        val providers = (1..60).map { provider("$it") }
        store.replaceAll(providers)
        val replacements = (1..20).map { provider("$it").copy(name = "edited-$it") }
        val changes = replacements.map { provider -> suspend { store.upsert(provider) } } +
            (21..40).map { id -> suspend { store.delete("$id") } } +
            (41..60).map { id -> suspend { store.setEnabled("$id", false) } }
        together(changes)
        assertThat(store.all()).containsExactlyElementsIn(
            replacements + providers.drop(40).map { it.copy(enabled = false) }
        )
    }

    @Test fun `upsert replaces existing entry once and explicit replacement removes previous list`() = runBlocking<Unit> {
        val store = store()
        store.upsert(provider("one"))
        val edited = provider("one").copy(name = "edited", enabled = false)
        store.upsert(edited)
        assertThat(store.all()).containsExactly(edited)
        store.replaceAll(listOf(provider("two")))
        assertThat(store.all()).containsExactly(provider("two"))
        store.delete("missing")
        store.setEnabled("missing", false)
        assertThat(store.all()).containsExactly(provider("two"))
    }

    private suspend fun together(changes: List<suspend () -> Unit>) = coroutineScope {
        val begin = CompletableDeferred<Unit>()
        val jobs = changes.map { change -> launch(Dispatchers.Default) { begin.await(); change() } }
        begin.complete(Unit)
        jobs.joinAll()
    }

    private fun provider(id: String) = ApiProvider(
        id = id, name = id, system = "sis", purpose = "timetable", method = "GET",
        url = "https://sis.slai.edu.cn/test/$id"
    )
}
