package com.slai.campus.core.session

import java.time.LocalDate

/** The anchor belongs to one academic year/term; server observations are never user confirmation. */
data class SemesterConfiguration(
    val year: String?,
    val term: String?,
    val anchor: LocalDate?,
    val userConfirmed: Boolean,
    val anchorYear: String?,
    val anchorTerm: String?,
    val verifiedAt: Long
) {
    fun anchorFor(year: String?, term: String?): Pair<LocalDate?, Boolean> {
        val sameTerm = !year.isNullOrBlank() && !term.isNullOrBlank() &&
            anchorYear == year && anchorTerm == term
        // A date selected before the first ever discovery can be bound to that first term.
        val pendingUserDate = this.year == null && this.term == null &&
            anchorYear == null && anchorTerm == null && userConfirmed
        return if (sameTerm || pendingUserDate) anchor to userConfirmed else null to false
    }

    fun canReuse(now: Long): Boolean =
        !year.isNullOrBlank() && !term.isNullOrBlank() && anchorFor(year, term).first != null &&
            verifiedAt > 0 && now >= verifiedAt && now - verifiedAt < VERIFICATION_INTERVAL_MS

    companion object {
        const val VERIFICATION_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
