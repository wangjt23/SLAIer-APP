package com.slai.campus.core.session

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

// Not a data class: generated toString/copy must never expose credentials.
internal class SavedLoginRecord(
    val credentials: SchoolCredentials,
    val enabled: Boolean,
    val paused: Boolean,
    val revision: Long,
    val retryAfter: Long = 0
) {
    fun canAttempt() = enabled && !paused && System.currentTimeMillis() >= retryAfter

    fun encode(): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).use {
            it.writeInt(2)
            it.writeUTF(credentials.username)
            it.writeUTF(credentials.password)
            it.writeBoolean(enabled)
            it.writeBoolean(paused)
            it.writeLong(revision)
            it.writeLong(retryAfter)
        }
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): SavedLoginRecord = DataInputStream(ByteArrayInputStream(bytes)).use {
            val version = it.readInt()
            require(version in 1..2)
            val credentials = SchoolCredentials(it.readUTF(), it.readUTF())
            val enabled = it.readBoolean()
            val paused = it.readBoolean()
            val revision = it.readLong()
            // v1 also paused on cancellation/timeouts; its bit cannot prove bad credentials.
            // Give the upgrade one bounded attempt instead of inheriting a permanent stop.
            SavedLoginRecord(credentials, enabled, version >= 2 && paused, revision,
                if (version >= 2) it.readLong() else 0)
        }
    }
}
