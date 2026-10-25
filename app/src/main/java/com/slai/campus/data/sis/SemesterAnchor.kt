package com.slai.campus.data.sis

import java.time.LocalDate

/** The selected teaching-week anchor and its source for the refresh trace. */
data class SemesterAnchor(val monday: LocalDate?, val source: String)

/** Shared priority for refreshes and diagnostics; persistence remains the caller's responsibility. */
fun resolveSemesterAnchor(
    storedAnchor: LocalDate?,
    confirmed: Boolean,
    observedAnchor: LocalDate?,
    pageAnchor: LocalDate?,
    academicYear: String?,
    termCode: String?
): SemesterAnchor = when {
    confirmed && storedAnchor != null -> SemesterAnchor(storedAnchor, "stored+confirmed")
    observedAnchor != null -> SemesterAnchor(observedAnchor, "server week")
    pageAnchor != null -> SemesterAnchor(pageAnchor, "page select")
    storedAnchor != null -> SemesterAnchor(storedAnchor, "stored")
    else -> SemesterAnchor(SemesterCalendarResolver.estimate(academicYear, termCode), "estimate")
}
