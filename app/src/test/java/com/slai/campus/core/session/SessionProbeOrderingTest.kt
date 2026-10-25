package com.slai.campus.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.SchoolSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionProbeOrderingTest {
    // Immediate preference reads let the scheduler deterministically queue the old state write
    // behind the real account mutex; all SessionManager code is exercised unchanged.
    private class ImmediateStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(mutablePreferencesOf(
            stringPreferencesKey("stu_account_hash") to "verified-owner",
            stringPreferencesKey("stu_state") to SessionState.AUTHENTICATED.name
        ))
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }

    @Test fun `old expiry waiting on account mutex cannot overwrite newly authenticated same owner`() = runTest {
        val probe = object : SessionProbe {
            override val system = SchoolSystem.STU
            override suspend fun probe() = SessionState.EXPIRED
        }
        val manager = SessionManager(SessionStore(ImmediateStore()), setOf(probe), backgroundScope)
        manager.set(SchoolSystem.STU, SessionState.AUTHENTICATED)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fresh = async {
            manager.withAccount(SchoolSystem.STU, "verified-owner", 0) {
                entered.complete(Unit)
                release.await()
                manager.set(SchoolSystem.STU, SessionState.AUTHENTICATED)
            }
        }
        runCurrent()
        entered.await()
        val expired = async { manager.probe(SchoolSystem.STU, requireFresh = true) }
        runCurrent()
        assertThat(expired.isCompleted).isFalse()
        release.complete(Unit)
        runCurrent()
        fresh.await()
        assertThat(expired.await()).isEqualTo(SessionState.ERROR)
        assertThat(manager.current().stu).isEqualTo(SessionState.AUTHENTICATED)
    }
}
