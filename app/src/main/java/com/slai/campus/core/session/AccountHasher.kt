package com.slai.campus.core.session

import java.security.MessageDigest
import java.util.UUID
import javax.inject.Singleton

/**
 * Derives the local cache partition key.
 *
 * Known school identifiers use a stable SHA-256 namespace so the same owner can recover its cache.
 * Room stores this key instead of a raw identifier; it is not encryption or a per-install salt.
 * Unknown accounts get a random key, persisted atomically by SessionStore.
 */
@Singleton
object AccountHasher {

    private const val HASH_LENGTH = 32

    /** Stable cache key for a server-proved student id. */
    fun hash(studentId: String): String {
        val normalized = studentId.trim().lowercase()
        return sha256("slai-campus::account::$normalized").take(HASH_LENGTH)
    }

    /**
     * Key used before the student id is known. Uses the user-provided hint when available so that
     * two people sharing a device do not read each other's cache.
     */
    fun deviceLocalHash(studentIdHint: String?): String {
        val hint = studentIdHint?.trim()?.takeIf { it.isNotEmpty() }
        return if (hint != null) hash(hint) else "local-" + sha256(UUID.randomUUID().toString()).take(HASH_LENGTH)
    }

    /** Non-secret display form for the diagnostics screen. */
    fun display(hash: String?): String = hash?.take(8)?.let { "$it…" } ?: "—"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
