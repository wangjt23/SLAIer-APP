package com.slai.campus.core.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.SchoolSystem
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountPartitionTest {
    @get:Rule val directory = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun prefs() = PreferenceDataStoreFactory.create(scope = scope) { directory.newFile("session.preferences_pb") }
    @After fun finish() { scope.cancel() }

    @Test fun `parallel first requests share exactly one partition`() = runBlocking {
        val store = SessionStore(prefs())
        val hashes = coroutineScope { (1..40).map { async(Dispatchers.Default) { store.requireAccountHash(SchoolSystem.STU) } }.awaitAll() }
        assertThat(hashes.distinct()).hasSize(1)
        assertThat(store.current().stuAccountHash).isEqualTo(hashes.first())
    }

    @Test fun `legacy SIS cache remains available without assigning it to STU`() = runBlocking {
        val data = prefs()
        data.edit { it[stringPreferencesKey("account_hash")] = "legacy-owner" }
        val store = SessionStore(data)
        assertThat(store.current().sisAccountHash).isEqualTo("legacy-owner")
        assertThat(store.current().stuAccountHash).isNull()
        assertThat(store.requireAccountHash(SchoolSystem.SIS)).isEqualTo("legacy-owner")
        assertThat(store.requireAccountHash(SchoolSystem.STU)).isNotEqualTo("legacy-owner")
        store.setAccountHash(SchoolSystem.SIS, null)
        assertThat(store.current().sisAccountHash).isNull()
    }

    @Test fun `switching STU owner leaves SIS partition intact`() = runBlocking {
        val store = SessionStore(prefs())
        val manager = SessionManager(store, emptySet(), scope)
        store.setAccountHash(SchoolSystem.SIS, "sis-owner")
        store.setAccountHash(SchoolSystem.STU, "stu-A")
        manager.beginLogin(SchoolSystem.STU)
        val owner = manager.withAccount(SchoolSystem.STU, "stu-B", manager.accountVersion(SchoolSystem.STU)) { it }
        assertThat(owner).isEqualTo("stu-B")
        assertThat(manager.current().stuAccountHash).isEqualTo("stu-B")
        assertThat(manager.current().sisAccountHash).isEqualTo("sis-owner")
    }

    @Test fun `a late response from before account change cannot write data`() = runBlocking {
        val manager = SessionManager(SessionStore(prefs()), emptySet(), scope)
        val oldVersion = manager.accountVersion(SchoolSystem.STU)
        manager.beginLogin(SchoolSystem.STU)
        var written = false
        val result = manager.withAccount(SchoolSystem.STU, "old-account", oldVersion) { written = true; it }
        assertThat(result).isNull()
        assertThat(written).isFalse()
    }

    @Test fun `cancelled login keeps cache but an authenticated unidentified account gets a new partition`() = runBlocking {
        val store = SessionStore(prefs())
        val manager = SessionManager(store, emptySet(), scope)
        store.setAccountHash(SchoolSystem.STU, "stu-A")
        manager.beginLogin(SchoolSystem.STU)
        assertThat(manager.current().stuAccountHash).isEqualTo("stu-A")
        val newOwner = manager.withAccount(SchoolSystem.STU, null, manager.accountVersion(SchoolSystem.STU)) { it }
        assertThat(newOwner).isNotEqualTo("stu-A")
        assertThat(store.accountPending(SchoolSystem.STU)).isFalse()
    }

    @Test fun `reauthentication of the same owner preserves the old cache partition`() = runBlocking {
        val store = SessionStore(prefs())
        val manager = SessionManager(store, emptySet(), scope)
        store.setAccountHash(SchoolSystem.STU, "stu-A")
        manager.beginLogin(SchoolSystem.STU)
        assertThat(manager.withAccount(SchoolSystem.STU, "stu-A", manager.accountVersion(SchoolSystem.STU)) { it }).isEqualTo("stu-A")
        assertThat(store.accountPending(SchoolSystem.STU)).isFalse()
    }

    @Test fun `explicit account change waits for an accepted cache write to finish`() = runBlocking {
        val manager = SessionManager(SessionStore(prefs()), emptySet(), scope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val write = async {
            manager.withAccount(SchoolSystem.SIS, "verified-owner", 0) { entered.complete(Unit); release.await(); it }
        }
        entered.await()
        val change = async { manager.beginLogin(SchoolSystem.SIS) }
        yield()
        assertThat(change.isCompleted).isFalse()
        release.complete(Unit)
        assertThat(write.await()).isEqualTo("verified-owner")
        change.await()
        assertThat(manager.accountVersion(SchoolSystem.SIS)).isEqualTo(2)
    }

    @Test fun `startup probe and month response from the same owner both remain usable`() = runBlocking {
        val manager = SessionManager(SessionStore(prefs()), emptySet(), scope)
        assertThat(manager.withAccount(SchoolSystem.STU, "verified-owner", 0) { "probe" }).isEqualTo("probe")
        assertThat(manager.withAccount(SchoolSystem.STU, "verified-owner", 0) { "month-saved" }).isEqualTo("month-saved")
        assertThat(manager.withAccount(SchoolSystem.STU, "different-owner", 0) { "wrong" }).isNull()
    }

    @Test fun `explicit reauthentication revokes same-owner startup response allowance`() = runBlocking {
        val manager = SessionManager(SessionStore(prefs()), emptySet(), scope)
        manager.withAccount(SchoolSystem.STU, "verified-owner", 0) { Unit }
        manager.beginLogin(SchoolSystem.STU)
        assertThat(manager.withAccount(SchoolSystem.STU, "verified-owner", 0) { "too-late" }).isNull()
    }

    @Test fun `read-only completion guard permits same-owner startup binding`() = runBlocking {
        val manager = SessionManager(SessionStore(prefs()), emptySet(), scope)
        manager.withAccount(SchoolSystem.STU, "verified-owner", 0) { Unit }
        assertThat(manager.withCurrentAccount(SchoolSystem.STU, "verified-owner", 0, null) { _, version -> version })
            .isEqualTo(1)
    }

    @Test fun `read-only completion guard does not clear a pending login`() = runBlocking {
        val store = SessionStore(prefs())
        val manager = SessionManager(store, emptySet(), scope)
        store.setAccountHash(SchoolSystem.STU, "verified-owner")
        manager.beginLogin(SchoolSystem.STU)
        assertThat(manager.withCurrentAccount(SchoolSystem.STU, "verified-owner", 1, null) { owner, _ -> owner })
            .isEqualTo("verified-owner")
        assertThat(store.accountPending(SchoolSystem.STU)).isTrue()
    }

    @Test fun `new explicit login rejects an old final result even for same owner`() = runBlocking {
        val manager = SessionManager(SessionStore(prefs()), emptySet(), scope)
        manager.withAccount(SchoolSystem.STU, "verified-owner", 0) { Unit }
        manager.beginLogin(SchoolSystem.STU)
        assertThat(manager.withCurrentAccount(SchoolSystem.STU, "verified-owner", 0, null) { _, _ -> "old" }).isNull()
    }

    @Test fun `concurrent authentication preserves valid success but rejects stale failure evidence`() = runBlocking {
        val store = SessionStore(prefs())
        val manager = SessionManager(store, emptySet(), scope)
        store.setAccountHash(SchoolSystem.STU, "verified-owner")
        val requestAuthentication = manager.authenticationVersion(SchoolSystem.STU)
        manager.set(SchoolSystem.STU, SessionState.AUTHENTICATED)
        assertThat(manager.withCurrentAccount(SchoolSystem.STU, "verified-owner", 0, null) { _, _ -> "success" })
            .isEqualTo("success")
        assertThat(manager.withCurrentAccount(SchoolSystem.STU, "verified-owner", 0, requestAuthentication) { _, _ -> "old failure" })
            .isNull()
    }

    @Test fun `late expiry cannot overwrite fresh proof for the same account`() = runBlocking {
        val store = SessionStore(prefs())
        val manager = SessionManager(store, emptySet(), scope)
        store.setAccountHash(SchoolSystem.STU, "verified-owner")
        manager.set(SchoolSystem.STU, SessionState.AUTHENTICATED)
        val oldProof = manager.authenticationVersion(SchoolSystem.STU)
        manager.withAccount(SchoolSystem.STU, "verified-owner", 0) {
            manager.set(SchoolSystem.STU, SessionState.AUTHENTICATED)
        }
        assertThat(manager.set(SchoolSystem.STU, SessionState.EXPIRED, 0, oldProof)).isFalse()
        assertThat(manager.current().stu).isEqualTo(SessionState.AUTHENTICATED)
    }

    @Test fun `old successful probe cannot complete a newer manual login`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<SessionState>()
        val probe = object : SessionProbe {
            override val system = SchoolSystem.SIS
            override suspend fun probe(): SessionState {
                entered.complete(Unit)
                return release.await()
            }
        }
        val manager = SessionManager(SessionStore(prefs()), setOf(probe), scope)
        manager.beginLogin(SchoolSystem.SIS)
        val oldLoginProbe = async { manager.probe(SchoolSystem.SIS, requireFresh = true) }
        entered.await()
        manager.beginLogin(SchoolSystem.SIS)
        release.complete(SessionState.AUTHENTICATED)
        assertThat(oldLoginProbe.await()).isEqualTo(SessionState.ERROR)
        assertThat(manager.current().sis).isEqualTo(SessionState.UNKNOWN)
        assertThat(manager.authenticationVersion(SchoolSystem.SIS)).isEqualTo(0)
        assertThat(manager.probe(SchoolSystem.SIS, requireFresh = true)).isEqualTo(SessionState.AUTHENTICATED)
    }
}
