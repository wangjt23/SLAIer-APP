package com.slai.campus.data.sis

import com.slai.campus.domain.schedule.ClassOccurrence
import com.slai.campus.domain.schedule.ScheduleSource
import com.slai.campus.domain.schedule.Semester
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** Inclusive week range with an optional odd/even constraint. */
data class WeekRange(val start: Int, val end: Int, val parity: Parity = Parity.ALL) {
    enum class Parity { ALL, ODD, EVEN }

    val weeks: IntRange get() = start..end

    fun matches(week: Int): Boolean = when (parity) {
        Parity.ALL -> true
        Parity.ODD -> week % 2 == 1
        Parity.EVEN -> week % 2 == 0
    }
}

/**
 * Turns ZFSoft's week rules into concrete dates.
 *
 * ZFSoft describes a session as "weekday 3, periods 5-6, weeks 1-16 (odd)". The UI must never do
 * this arithmetic, so it happens exactly once, here, and the result is stored as a date.
 */
object SisScheduleNormalizer {

    /** Fallback period start times, used only when the server does not send `zsMap`/`sjkList`. */
    private val DEFAULT_PERIOD_START: Map<Int, LocalTime> = mapOf(
        1 to LocalTime.of(8, 0),
        2 to LocalTime.of(8, 55),
        3 to LocalTime.of(9, 55),
        4 to LocalTime.of(10, 50),
        5 to LocalTime.of(11, 45),
        6 to LocalTime.of(13, 50),
        7 to LocalTime.of(14, 45),
        8 to LocalTime.of(15, 45),
        9 to LocalTime.of(16, 40),
        10 to LocalTime.of(18, 30),
        11 to LocalTime.of(19, 25),
        12 to LocalTime.of(20, 20)
    )

    private const val DEFAULT_PERIOD_MINUTES = 45L

    private val FULL_WIDTH = mapOf('（' to '(', '）' to ')', '，' to ',', '、' to ',', '－' to '-', '～' to '~')

    /**
     * Parses a ZFSoft week description.
     *
     * Accepted shapes (all observed in the wild across ZFSoft versions):
     *  - `1-16周`, `第1-16周`, `1-16`
     *  - `1-8周,10-16周`
     *  - `1-16周(单)`, `2-16周(双)`, `1-16周（单周）`
     *  - `1,3,5,7周`
     */
    fun parseWeekRanges(weekDescription: String?, parityFlag: String? = null): List<WeekRange> {
        val raw = weekDescription?.trim().orEmpty()
        val normalized = buildString {
            raw.forEach { append(FULL_WIDTH[it] ?: it) }
        }.replace(" ", "")

        // Parity comes from the text only. The live deployment sets `sxbj=1` on every course,
        // including plain "1-14周" ones, so treating it as "odd weeks" would drop half the timetable.
        val parity = when {
            normalized.contains("单") -> WeekRange.Parity.ODD
            normalized.contains("双") -> WeekRange.Parity.EVEN
            else -> WeekRange.Parity.ALL
        }
        @Suppress("UNUSED_PARAMETER") val ignoredFlag = parityFlag

        val digitsOnly = normalized.replace(Regex("[^0-9,\\-]"), "")
        if (digitsOnly.isBlank()) return emptyList()

        val ranges = mutableListOf<WeekRange>()

        Regex("(\\d{1,3})\\s*-\\s*(\\d{1,3})").findAll(digitsOnly).forEach { m ->
            val start = m.groupValues[1].toIntOrNull() ?: return@forEach
            val end = m.groupValues[2].toIntOrNull() ?: return@forEach
            if (start in 1..60 && end in start..60) {
                ranges += WeekRange(start, end, parity)
            }
        }

        // Standalone weeks not part of a range, e.g. "1,3,5,7周".
        digitsOnly.split(',').forEach { token ->
            val n = token.trim().toIntOrNull() ?: return@forEach
            if (n in 1..60 && ranges.none { n in it.weeks }) {
                ranges += WeekRange(n, n, parity)
            }
        }

        return ranges.distinct().sortedBy { it.start }
    }

    /**
     * Expands a ZFSoft week bitmask (`oldzc`), where bit 0 is week 1.
     *
     * `16383` -> `0b11111111111111` -> weeks 1..14, which is exactly what `zcd = "1-14周"` says.
     * Contiguous runs are merged so that 1..8 + 10..16 stays two ranges.
     */
    fun weeksFromBitmask(mask: Long): List<WeekRange> {
        if (mask <= 0L) return emptyList()
        val weeks = (1..60).filter { (mask shr (it - 1)) and 1L == 1L }
        if (weeks.isEmpty()) return emptyList()
        val ranges = mutableListOf<WeekRange>()
        var start = weeks.first()
        var previous = start
        weeks.drop(1).forEach { week ->
            if (week == previous + 1) {
                previous = week
            } else {
                ranges += WeekRange(start, previous)
                start = week
                previous = week
            }
        }
        ranges += WeekRange(start, previous)
        return ranges
    }

    /** Parses `"1-2"`, `"01-02"`, `"3"` into an inclusive period range. */
    fun parsePeriods(periods: String?): IntRange? {
        val text = periods?.trim().orEmpty()
        if (text.isEmpty()) return null
        val numbers = Regex("\\d{1,2}").findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
        if (numbers.isEmpty()) return null
        val start = numbers.first()
        val end = numbers.getOrNull(1) ?: start
        if (start !in 1..20) return null
        return start..(end.coerceIn(start, 20))
    }

    /** Builds the period -> start-time table, preferring server data over the fallback. */
    fun periodTable(
        response: SisTimetableResponse?,
        explicit: List<Pair<LocalTime, LocalTime>>? = null
    ): Map<Int, LocalTime> {
        // An explicit table (from the school's 节次时间 endpoint) wins over everything.
        if (!explicit.isNullOrEmpty()) {
            return explicit.mapIndexed { index, pair -> (index + 1) to pair.first }.toMap()
        }

        val table = mutableMapOf<Int, LocalTime>()

        response?.zsMap?.forEach { (key, value) ->
            val index = key.trim().toIntOrNull() ?: return@forEach
            val time = parseClock(value) ?: return@forEach
            table[index] = time
        }

        response?.sjkList?.forEach { item ->
            val index = item.periodIndex?.trim()?.toIntOrNull()
                ?: item.periodNumber?.trim()?.toIntOrNull()
                ?: return@forEach
            if (table.containsKey(index)) return@forEach
            val time = item.startTime?.let(::parseClock)
                ?: item.timeRange?.substringBefore('-')?.let(::parseClock)
                ?: return@forEach
            table[index] = time
        }

        return if (table.isEmpty()) DEFAULT_PERIOD_START else table
    }

    /** Accepts `"08:00"`, `"8:00"`, `"08:00:00"`, `"08:00-08:45"`. */
    fun parseClock(value: String?): LocalTime? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        val candidate = text.substringBefore('-').trim().substringBefore('~').trim()
        val parts = candidate.split(':')
        if (parts.size < 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    /** End time for a period range: start of the last period plus one class slot. */
    private fun endTimeFor(periodRange: IntRange, table: Map<Int, LocalTime>): LocalTime {
        val lastStart = table[periodRange.last] ?: table[periodRange.first] ?: LocalTime.of(8, 0)
        return lastStart.plusMinutes(DEFAULT_PERIOD_MINUTES)
    }

    /**
     * Expands raw rows into dated occurrences.
     *
     * Returns an empty list when the semester is not anchored: without week 1 we cannot invent dates,
     * and inventing them would silently show the wrong classes on the wrong day.
     */
    fun normalize(
        items: List<SisKbItem>,
        semester: Semester,
        response: SisTimetableResponse?,
        source: ScheduleSource,
        accountHash: String,
        periodTimes: List<Pair<LocalTime, LocalTime>>? = null
    ): NormalizeOutcome {
        val anchor = semester.firstWeekMonday
            ?: return NormalizeOutcome(emptyList(), items.size, "semester not anchored")

        val table = periodTable(response, periodTimes)
        val occurrences = mutableListOf<ClassOccurrence>()
        var skipped = 0

        items.forEach { item ->
            val courseName = item.courseName?.trim()
            val weekday = item.weekday?.trim()?.toIntOrNull()
            val periodRange = parsePeriods(item.periods ?: item.periodsRaw ?: item.periodText)
            val mask = item.oldWeekMask?.trim()?.toLongOrNull()
            val ranges = if (mask != null && mask > 0L) {
                weeksFromBitmask(mask)
            } else {
                parseWeekRanges(item.weekDescription, item.parityFlag)
            }

            if (courseName.isNullOrEmpty() || weekday == null || weekday !in 1..7 || periodRange == null) {
                skipped++
                return@forEach
            }
            if (ranges.isEmpty()) {
                skipped++
                return@forEach
            }

            val start = table[periodRange.first] ?: DEFAULT_PERIOD_START[periodRange.first]
            if (start == null) {
                skipped++
                return@forEach
            }
            val end = endTimeFor(periodRange, table)

            for (range in ranges) {
                for (week in range.weeks) {
                    if (week < 1 || week > 60) continue
                    if (!range.matches(week)) continue
                    val date = anchor.plusWeeks((week - 1).toLong()).plusDays((weekday - 1).toLong())
                    occurrences += ClassOccurrence(
                        id = occurrenceId(accountHash, date, start, item, periodRange, week),
                        courseName = courseName,
                        teacher = item.teacher?.trim()?.takeIf { it.isNotEmpty() },
                        location = item.location?.trim()?.takeIf { it.isNotEmpty() },
                        date = date,
                        startTime = start,
                        endTime = end,
                        source = source,
                        weekIndex = week,
                        periodStart = periodRange.first,
                        periodEnd = periodRange.last,
                        courseCode = (item.courseCode ?: item.courseCodeId)?.trim()?.takeIf { it.isNotEmpty() },
                        teachingClass = item.teachingClass?.trim()?.takeIf { it.isNotEmpty() },
                        campus = item.campus?.trim()?.takeIf { it.isNotEmpty() }
                    )
                }
            }
        }

        return NormalizeOutcome(
            occurrences = occurrences.distinctBy { it.id }.sortedWith(compareBy({ it.date }, { it.startTime })),
            skipped = skipped,
            warning = if (skipped > 0) "$skipped row(s) skipped" else null
        )
    }

    private fun occurrenceId(
        accountHash: String,
        date: LocalDate,
        start: LocalTime,
        item: SisKbItem,
        periodRange: IntRange,
        week: Int
    ): String {
        val code = (item.courseCode ?: item.courseCodeId ?: "").trim()
        val klass = item.teachingClass?.trim().orEmpty()
        val name = item.courseName?.trim().orEmpty()
        return listOf(
            accountHash.take(8),
            date.toString(),
            start.toString(),
            periodRange.first.toString(),
            code.ifEmpty { name },
            klass
        ).joinToString("|")
    }

    data class NormalizeOutcome(
        val occurrences: List<ClassOccurrence>,
        val skipped: Int,
        val warning: String?
    )
}

/**
 * Resolves the calendar anchor (Monday of teaching week 1).
 *
 * Order of evidence, strongest first:
 *  1. a value the user confirmed in Settings;
 *  2. a week number observed on the school page (page says "第 N 周" -> anchor = monday(today) - (N-1));
 *  3. a term-based estimate, clearly surfaced in the UI as an estimate.
 */
object SemesterCalendarResolver {

    /** Monday of the first teaching week, estimated from the term code. */
    fun estimate(academicYear: String?, termCode: String?): LocalDate? {
        val year = academicYear?.trim()?.take(4)?.toIntOrNull() ?: return null
        return when (termCode?.trim()) {
            SisConfig.Terms.FIRST -> firstMondayOf(year, 9)
            SisConfig.Terms.SECOND -> firstMondayOf(year + 1, 2)
            SisConfig.Terms.THIRD -> firstMondayOf(year + 1, 7)
            else -> null
        }
    }

    /**
     * Last-resort term guess from today's date, used only when neither the server nor storage knows.
     * The response itself reports the true term (`xsxx.XNM/XQM`), so this is corrected on first sync.
     */
    fun estimateTermFromDate(today: java.time.LocalDate): Pair<String, String> {
        val month = today.monthValue
        val year = today.year
        return when {
            month >= 8 -> year.toString() to SisConfig.Terms.FIRST
            month == 7 -> (year - 1).toString() to SisConfig.Terms.THIRD
            month >= 2 -> (year - 1).toString() to SisConfig.Terms.SECOND
            else -> (year - 1).toString() to SisConfig.Terms.FIRST
        }
    }

    /** Derives the anchor from an observed "current teaching week" and today's date. */
    fun fromObservedWeek(weekIndex: Int, today: LocalDate): LocalDate? {
        if (weekIndex !in 1..60) return null
        val monday = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        return monday.minusWeeks((weekIndex - 1).toLong())
    }

    /**
     * 第一周的星期一（兜底估算）。
     *
     * 这个值只在拿不到服务端 `week` 时才会用到。教务系统的 `index_cxCurrentSemester.html`
     * 直接返回 `{"year":"2026-2027","semester":"1","week":"1"}`，`week` 就是当前教学周，
     * 由它反推锚点比估算可靠得多。
     *
     * 实测：2026-09-09 服务端 week=1 → 第 1 周 = 2026-09-07 起，与"9 月第一个星期一"一致。
     * （注意考勤系统的"第1周"是 08-31 起，两套编号不同，课表以教务系统的为准。）
     */
    private fun firstMondayOf(year: Int, month: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY))
}
