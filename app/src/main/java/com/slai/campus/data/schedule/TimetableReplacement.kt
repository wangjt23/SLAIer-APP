package com.slai.campus.data.schedule

import com.slai.campus.data.sis.SisTimetableParsed

/** Partial DOM weeks and provider arrays cannot prove that the entire semester is empty. */
internal fun canReplaceTimetable(parsed: SisTimetableParsed, fullSemester: Boolean): Boolean =
    parsed.occurrences.isNotEmpty() ||
        (fullSemester && parsed.trustedEmpty && parsed.rawItemCount == 0 && parsed.skippedRows == 0)
