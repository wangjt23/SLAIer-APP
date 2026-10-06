package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Test

class SavedLoginRecordTest {
    @Test fun `legacy timeout pause does not permanently disable login after upgrade`() {
        for (enabled in listOf(false, true)) {
            val bytes = ByteArrayOutputStream().apply {
                DataOutputStream(this).use {
                    it.writeInt(1); it.writeUTF("test@example.invalid"); it.writeUTF("test-only")
                    it.writeBoolean(enabled); it.writeBoolean(true); it.writeLong(123)
                }
            }.toByteArray()
            val record = SavedLoginRecord.decode(bytes)
            assertThat(record.enabled).isEqualTo(enabled)
            assertThat(record.paused).isFalse()
            assertThat(record.retryAfter).isEqualTo(0)
            assertThat(record.revision).isEqualTo(123)
            assertThat(record.credentials.username).isEqualTo("test@example.invalid")
        }
    }

    @Test fun `new manual pause and retry cooldown survive persisted round trip`() {
        for (paused in listOf(false, true)) {
            val record = SavedLoginRecord(SchoolCredentials("test@example.invalid", "test-only"), true, paused, 123, 456)
            val decoded = SavedLoginRecord.decode(record.encode())
            assertThat(decoded.paused).isEqualTo(paused)
            assertThat(decoded.retryAfter).isEqualTo(456)
            assertThat(decoded.revision).isEqualTo(123)
            assertThat(decoded.credentials.password).isEqualTo("test-only")
        }
    }
}
