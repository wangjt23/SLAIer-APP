package com.slai.campus.core.session

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.slai.campus.core.common.SchoolSystem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real encrypted storage and Android Keystore, with synthetic credentials in an isolated directory. */
@RunWith(AndroidJUnit4::class)
class SavedLoginSystemIsolationTest {
    @Test fun staleAutomaticAttemptCannotReadOrCoolDownAReplacementAccount() = runBlocking<Unit> {
        withStore { store, context ->
            store.save("first-student@example.invalid", "synthetic-first-password")
            val oldRevision = store.status.value.revision
            awaitNextRevision(oldRevision)
            store.save("replacement-student@example.invalid", "synthetic-replacement-password")
            val replacement = store.status.value
            assertNotEquals(oldRevision, replacement.revision)

            assertNull(store.credentials(expectedRevision = oldRevision))
            assertFalse(store.beginAttempt(expectedRevision = oldRevision))
            assertEquals(replacement, store.status.value)
            assertEquals(replacement, SavedLoginStore(context).status.value)

            assertEquals("replacement-student@example.invalid",
                store.credentials(expectedRevision = replacement.revision)?.username)
            assertTrue(store.beginAttempt(expectedRevision = replacement.revision))
            assertFalse(SavedLoginStore(context).status.value.canAttempt)
        }
    }

    @Test fun staleAutomaticPauseCannotChangeAReplacementSavedAccount() = runBlocking<Unit> {
        withStore { store, context ->
            store.save("first-student@example.invalid", "synthetic-first-password")
            val oldRevision = store.status.value.revision
            awaitNextRevision(oldRevision)
            store.save("replacement-student@example.invalid", "synthetic-replacement-password")
            val replacement = store.status.value
            assertNotEquals(oldRevision, replacement.revision)

            store.pauseAutomaticLogin(expectedRevision = oldRevision)
            assertEquals(replacement, store.status.value)
            assertEquals(replacement, SavedLoginStore(context).status.value)
            assertEquals("replacement-student@example.invalid", store.credentials()?.username)

            store.pauseAutomaticLogin(expectedRevision = replacement.revision)
            assertTrue(SavedLoginStore(context).status.value.paused)
        }
    }

    @Test fun staleAutomaticSuccessCannotClearANewerSettingsCooldown() = runBlocking<Unit> {
        withStore { store, context ->
            store.save("test-student@example.invalid", "synthetic-password")
            val oldRevision = store.status.value.revision
            awaitNextRevision(oldRevision)
            store.setEnabled(false)
            store.setEnabled(true)
            assertTrue(store.beginAttempt())
            val coolingDown = store.status.value
            assertNotEquals(oldRevision, coolingDown.revision)
            assertFalse(coolingDown.canAttempt)

            store.loginSucceeded(expectedRevision = oldRevision)
            assertEquals(coolingDown, store.status.value)
            assertEquals(coolingDown, SavedLoginStore(context).status.value)

            store.loginSucceeded(expectedRevision = coolingDown.revision)
            assertEquals(0L, SavedLoginStore(context).status.value.retryAfter)
        }
    }

    @Test fun sisSignOutAndSignInPreserveStudentLoginPauseAcrossReload() = runBlocking<Unit> {
        withStore { store, context ->
            store.save("test-student@example.invalid", "synthetic-password")
            store.pauseAutomaticLogin()
            val original = store.status.value
            store.setEnabled(false, SchoolSystem.SIS)
            store.loginSucceeded(SchoolSystem.SIS)
            assertEquals(original, store.status.value)
            assertEquals(original, SavedLoginStore(context).status.value)
            assertTrue(original.enabled)
            assertTrue(original.paused)
            store.loginSucceeded(SchoolSystem.STU)
            assertFalse(store.status.value.paused)
            assertTrue(store.status.value.enabled)
        }
    }

    @Test fun sisLoginDoesNotResetStudentCooldownAndStudentSignOutDisablesTheSavedLogin() = runBlocking<Unit> {
        withStore { store, context ->
            store.save("test-student@example.invalid", "synthetic-password")
            assertTrue(store.beginAttempt())
            val coolingDown = store.status.value
            assertFalse(coolingDown.canAttempt)
            store.loginSucceeded(SchoolSystem.SIS)
            assertEquals(coolingDown, SavedLoginStore(context).status.value)
            store.setEnabled(false, SchoolSystem.STU)
            assertFalse(SavedLoginStore(context).status.value.enabled)
            store.setEnabled(true, SchoolSystem.STU)
            assertTrue(SavedLoginStore(context).status.value.canAttempt)
        }
    }

    private suspend fun withStore(block: suspend (SavedLoginStore, Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.noBackupFilesDir, "saved-login-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir(): File = directory }
        val store = SavedLoginStore(context)
        try { block(store, context) }
        finally { store.clear(); directory.deleteRecursively() }
    }

    private suspend fun awaitNextRevision(previous: Long) {
        while (System.currentTimeMillis() <= previous) delay(1)
    }
}
