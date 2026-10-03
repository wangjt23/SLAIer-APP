package com.slai.campus.core.session

import com.slai.campus.core.common.SchoolSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** Only live, successful protected requests enter this in-memory sequence. */
internal class AuthenticationEvidence {
    private val versions = MutableStateFlow<Map<SchoolSystem, Long>>(emptyMap())
    fun version(system: SchoolSystem): Long = versions.value[system] ?: 0L
    fun confirmed(system: SchoolSystem) {
        versions.update { it + (system to ((it[system] ?: 0L) + 1L)) }
    }
    suspend fun awaitAfter(system: SchoolSystem, version: Long) {
        versions.first { (it[system] ?: 0L) > version }
    }
}
