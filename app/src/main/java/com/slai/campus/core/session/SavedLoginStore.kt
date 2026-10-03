package com.slai.campus.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

data class SavedLoginStatus(
    val saved: Boolean = false,
    val enabled: Boolean = false,
    val paused: Boolean = false,
    val revision: Long = 0
) {
    val canAttempt: Boolean get() = saved && enabled && !paused
}

// Deliberately not a data class: toString must never print credentials.
class SchoolCredentials(val username: String, val password: String)

@Singleton
class SavedLoginStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "school-login.enc"))
    private val _status = MutableStateFlow(SavedLoginStatus())
    val status = _status.asStateFlow()

    private class Record(val credentials: SchoolCredentials, val enabled: Boolean, val paused: Boolean, val revision: Long)

    init {
        // No plaintext is retained in a Flow, log, preferences or saved instance state.
        synchronized(this) { read()?.let { publish(it) } }
    }

    suspend fun save(username: String, password: String) = withContext(Dispatchers.IO) {
        require(username.isNotBlank() && password.isNotEmpty())
        synchronized(this@SavedLoginStore) {
            write(Record(SchoolCredentials(username.trim(), password), true, false, System.currentTimeMillis()))
        }
    }

    suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        synchronized(this@SavedLoginStore) {
            read()?.let { write(Record(it.credentials, enabled, false, System.currentTimeMillis())) }
        }
    }

    suspend fun credentials(): SchoolCredentials? = withContext(Dispatchers.IO) {
        synchronized(this@SavedLoginStore) {
            read()?.takeIf { it.enabled && !it.paused }?.credentials
        }
    }

    /** Persist before password submission, so a crash/restart cannot cause another attempt. */
    suspend fun beginAttempt(): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@SavedLoginStore) {
            val record = read()?.takeIf { it.enabled && !it.paused } ?: return@synchronized false
            write(Record(record.credentials, record.enabled, true, record.revision))
            true
        }
    }

    suspend fun loginSucceeded() = withContext(Dispatchers.IO) {
        synchronized(this@SavedLoginStore) {
            read()?.let { write(Record(it.credentials, it.enabled, false, it.revision)) }
        }
    }

    /** Captcha, timeout and transport failures also require an explicit manual recovery. */
    suspend fun pauseAutomaticLogin() = withContext(Dispatchers.IO) {
        synchronized(this@SavedLoginStore) {
            read()?.let { write(Record(it.credentials, it.enabled, true, it.revision)) }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(this@SavedLoginStore) {
            file.delete()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keys.containsAlias(ALIAS)) keys.deleteEntry(ALIAS)
            _status.value = SavedLoginStatus(revision = System.currentTimeMillis())
        }
    }

    private fun key(create: Boolean): SecretKey? {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun read(): Record? {
        if (!file.baseFile.exists()) return null
        return runCatching {
            val plaintext = LoginCipher.decrypt(requireNotNull(key(false)), file.readFully())
            try {
                DataInputStream(ByteArrayInputStream(plaintext)).use {
                    require(it.readInt() == 1)
                    Record(SchoolCredentials(it.readUTF(), it.readUTF()), it.readBoolean(), it.readBoolean(), it.readLong())
                }
            } finally { plaintext.fill(0) }
        }.getOrElse {
            // Missing/invalid key or corrupt ciphertext: require explicit re-entry, never use stale data.
            file.delete()
            _status.value = SavedLoginStatus()
            null
        }
    }

    private fun write(record: Record) {
        val bytes = ByteArrayOutputStream().apply {
            DataOutputStream(this).use {
                it.writeInt(1)
                it.writeUTF(record.credentials.username)
                it.writeUTF(record.credentials.password)
                it.writeBoolean(record.enabled)
                it.writeBoolean(record.paused)
                it.writeLong(record.revision)
            }
        }.toByteArray()
        val encrypted = try { LoginCipher.encrypt(requireNotNull(key(true)), bytes) } finally { bytes.fill(0) }
        val output = file.startWrite()
        try {
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        publish(record)
    }

    private fun publish(record: Record) {
        _status.value = SavedLoginStatus(true, record.enabled, record.paused, record.revision)
    }

    private companion object { const val ALIAS = "slai-school-login-v1" }
}
