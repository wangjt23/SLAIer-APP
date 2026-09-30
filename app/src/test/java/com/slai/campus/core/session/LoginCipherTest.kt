package com.slai.campus.core.session

import com.google.common.truth.Truth.assertThat
import javax.crypto.KeyGenerator
import org.junit.Test

class LoginCipherTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun `credentials survive round trip and are not stored as plaintext`() {
        val key = key()
        val bytes = "student@example.edu\u0000fake-password-测试".toByteArray()
        val encrypted = LoginCipher.encrypt(key, bytes)
        assertThat(LoginCipher.decrypt(key, encrypted)).isEqualTo(bytes)
        assertThat(encrypted.toString(Charsets.UTF_8)).doesNotContain("fake-password")
        assertThat(LoginCipher.encrypt(key, bytes)).isNotEqualTo(encrypted)
    }

    @Test fun `tampered ciphertext and another device key are rejected`() {
        val key = key()
        val encrypted = LoginCipher.encrypt(key, "test-only-secret".toByteArray())
        val changed = encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThat(runCatching { LoginCipher.decrypt(key, changed) }.isFailure).isTrue()
        assertThat(runCatching { LoginCipher.decrypt(key(), encrypted) }.isFailure).isTrue()
        assertThat(runCatching { LoginCipher.decrypt(key, byteArrayOf(1)) }.isFailure).isTrue()
    }
}
