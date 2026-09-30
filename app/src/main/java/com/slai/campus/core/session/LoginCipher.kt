package com.slai.campus.core.session

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The key remains in Android Keystore; only IV + authenticated ciphertext goes on disk. */
internal object LoginCipher {
    private val aad = "SLAIer saved school login v1".toByteArray(Charsets.UTF_8)

    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        require(cipher.iv.size == 12)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(key: SecretKey, encrypted: ByteArray): ByteArray {
        require(encrypted.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
    }
}
