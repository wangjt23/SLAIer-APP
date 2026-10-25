package com.slai.campus.data.schedule

import com.slai.campus.domain.schedule.ClassOccurrence

/** The full owner key participates in the primary key, not the parser's temporary partition. */
internal fun ClassOccurrence.accountCacheId(accountHash: String): String = listOf(
    accountHash, date.toString(), startTime.toString(), periodStart?.toString().orEmpty(),
    courseCode?.trim()?.takeIf { it.isNotEmpty() } ?: courseName.trim(), teachingClass?.trim().orEmpty()
).joinToString("|")
