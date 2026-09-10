package com.slai.campus.core.session

import java.security.MessageDigest
import java.util.UUID
import javax.inject.Singleton

/**
 * Derives the local cache partition key.
 *
 * The plan requires that switching accounts never shows the previous account's timetable, and that
 * the identifier stored in the database is not reversible. A salted SHA-256 of the student id is
 * enough: the salt is generated per install and never leaves the device, so the hash cannot be
 * rainbow-tabled back to a student number, and it is stable across restarts for the same account.
 */
@Singleton
object AccountHasher {

    private const val HASH_LENGTH = 32

    /** Stable, non-reversible key for a known student id. */
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
