package com.slai.campus.data.sis

import com.google.common.truth.Truth.assertThat
import com.slai.campus.domain.schedule.ScheduleSource
import com.slai.campus.domain.schedule.Semester
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * The normalizer is the single place where "第 N 周" becomes a date, so these cases cover the shapes
 * the plan's test matrix calls out: odd/even weeks, multi-range weeks, consecutive periods, missing
 * teacher/room, and the "cannot anchor" case.
 */
class SisScheduleNormalizerTest {

    private val anchor = LocalDate.of(2026, 9, 7) // a Monday
    private val semester = Semester(
        academicYear = "2026",
        termCode = "3",
        displayName = "2026-第一学期",
        firstWeekMonday = anchor
    )

    // ---- week parsing ---------------------------------------------------------------

    @Test
    fun `parses simple range`() {
        val ranges = SisScheduleNormalizer.parseWeekRanges("1-16周")
        assertThat(ranges).hasSize(1)
        assertThat(ranges.first().start).isEqualTo(1)
        assertThat(ranges.first().end).isEqualTo(16)
        assertThat(ranges.first().parity).isEqualTo(WeekRange.Parity.ALL)
    }

    @Test
    fun `parses multiple ranges`() {
        val ranges = SisScheduleNormalizer.parseWeekRanges("1-8周,10-16周")
        assertThat(ranges.map { it.start to it.end })
            .containsExactly(1 to 8, 10 to 16)
    }

    @Test
    fun `parses odd and even markers`() {
        assertThat(SisScheduleNormalizer.parseWeekRanges("1-16周(单)").first().parity)
            .isEqualTo(WeekRange.Parity.ODD)
        assertThat(SisScheduleNormalizer.parseWeekRanges("2-16周（双）").first().parity)
            .isEqualTo(WeekRange.Parity.EVEN)
    }

    @Test
    fun `sxbj is ignored because live data sets it on every course`() {
        // 深圳河套学院 returns sxbj="1" for plain "1-14周" courses, so it cannot mean "odd weeks".
        assertThat(SisScheduleNormalizer.parseWeekRanges("1-16周", parityFlag = "1").first().parity)
            .isEqualTo(WeekRange.Parity.ALL)
        assertThat(SisScheduleNormalizer.parseWeekRanges("1-16周", parityFlag = "2").first().parity)
            .isEqualTo(WeekRange.Parity.ALL)
    }

    @Test
    fun `parses comma separated single weeks`() {
        val ranges = SisScheduleNormalizer.parseWeekRanges("1,3,5,7周")
        assertThat(ranges.map { it.start }).containsExactly(1, 3, 5, 7)
    }

    @Test
    fun `returns empty for unparsable input`() {
        assertThat(SisScheduleNormalizer.parseWeekRanges("待定")).isEmpty()
        assertThat(SisScheduleNormalizer.parseWeekRanges(null)).isEmpty()
    }

    // ---- period parsing -------------------------------------------------------------

    @Test
    fun `parses period ranges`() {
        assertThat(SisScheduleNormalizer.parsePeriods("1-2")).isEqualTo(1..2)
        assertThat(SisScheduleNormalizer.parsePeriods("03-04")).isEqualTo(3..4)
        assertThat(SisScheduleNormalizer.parsePeriods("5")).isEqualTo(5..5)
        assertThat(SisScheduleNormalizer.parsePeriods("第3-4节")).isEqualTo(3..4)
        assertThat(SisScheduleNormalizer.parsePeriods(null)).isNull()
    }

    // ---- expansion ------------------------------------------------------------------

    @Test
    fun `expands a row into dated occurrences`() {
        val response = SisTimetableResponse(
            kbList = emptyList(),
            zsMap = mapOf("1" to "08:00", "2" to "08:55")
        )
        val item = SisKbItem(
            courseName = "数值分析",
            teacher = "张三",
            location = "A201",
            weekDescription = "1-2周",
            weekday = "1",
            periods = "1-2",
            courseCode = "MATH101"
        )

        val outcome = SisScheduleNormalizer.normalize(
            items = listOf(item),
            semester = semester,
            response = response,
            source = ScheduleSource.SIS_NATIVE,
            accountHash = "hash"
        )

        assertThat(outcome.occurrences).hasSize(2)
        val first = outcome.occurrences.first()
        assertThat(first.date).isEqualTo(anchor)
        assertThat(first.startTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(first.endTime).isEqualTo(LocalTime.of(9, 40)) // 08:55 + 45min
        assertThat(first.weekIndex).isEqualTo(1)
        assertThat(first.periodStart).isEqualTo(1)
        assertThat(first.periodEnd).isEqualTo(2)
        assertThat(first.source).isEqualTo(ScheduleSource.SIS_NATIVE)
    }

    @Test
    fun `odd week filter skips even weeks`() {
        val item = SisKbItem(
            courseName = "机器学习",
            weekDescription = "1-4周(单)",
            weekday = "2",
            periods = "3-4"
        )
        val outcome = SisScheduleNormalizer.normalize(
            listOf(item), semester, SisTimetableResponse(), ScheduleSource.SIS_NATIVE, "hash"
        )
        assertThat(outcome.occurrences.map { it.weekIndex }).containsExactly(1, 3)
    }

    @Test
    fun `same weekday offset maps to the right date`() {
        val item = SisKbItem(
            courseName = "英语",
            weekDescription = "1周",
            weekday = "7", // Sunday
            periods = "5-6"
        )
        val outcome = SisScheduleNormalizer.normalize(
            listOf(item), semester, SisTimetableResponse(), ScheduleSource.SIS_NATIVE, "hash"
        )
        assertThat(outcome.occurrences.single().date).isEqualTo(anchor.plusDays(6))
    }

    @Test
    fun `row with missing weekday is skipped not silently dropped`() {
        val item = SisKbItem(courseName = "缺字段", weekDescription = "1周", weekday = null, periods = "1-2")
        val outcome = SisScheduleNormalizer.normalize(
            listOf(item), semester, SisTimetableResponse(), ScheduleSource.SIS_NATIVE, "hash"
        )
        assertThat(outcome.occurrences).isEmpty()
        assertThat(outcome.skipped).isEqualTo(1)
    }

    @Test
    fun `unanchored semester produces no occurrences`() {
        val item = SisKbItem(courseName = "课", weekDescription = "1周", weekday = "1", periods = "1-2")
        val outcome = SisScheduleNormalizer.normalize(
            listOf(item),
            Semester(firstWeekMonday = null),
            SisTimetableResponse(),
            ScheduleSource.SIS_NATIVE,
            "hash"
        )
        assertThat(outcome.occurrences).isEmpty()
        assertThat(outcome.warning).contains("not anchored")
    }

    @Test
    fun `conflicting courses at the same time are both kept`() {
        val a = SisKbItem(courseName = "A", weekDescription = "1周", weekday = "1", periods = "1-2")
        val b = SisKbItem(courseName = "B", weekDescription = "1周", weekday = "1", periods = "1-2")
        val outcome = SisScheduleNormalizer.normalize(
            listOf(a, b), semester, SisTimetableResponse(), ScheduleSource.SIS_NATIVE, "hash"
        )
        assertThat(outcome.occurrences).hasSize(2)
    }

    @Test
    fun `null teacher and location are preserved as null`() {
        val item = SisKbItem(courseName = "自习", weekDescription = "1周", weekday = "1", periods = "1-2")
        val occurrence = SisScheduleNormalizer.normalize(
            listOf(item), semester, SisTimetableResponse(), ScheduleSource.SIS_NATIVE, "hash"
        ).occurrences.single()
        assertThat(occurrence.teacher).isNull()
        assertThat(occurrence.location).isNull()
    }

    // ---- clock parsing --------------------------------------------------------------

    @Test
    fun `parses clock variants`() {
        assertThat(SisScheduleNormalizer.parseClock("08:00")).isEqualTo(LocalTime.of(8, 0))
        assertThat(SisScheduleNormalizer.parseClock("8:05")).isEqualTo(LocalTime.of(8, 5))
        assertThat(SisScheduleNormalizer.parseClock("08:00:00")).isEqualTo(LocalTime.of(8, 0))
        assertThat(SisScheduleNormalizer.parseClock("08:00-08:45")).isEqualTo(LocalTime.of(8, 0))
        assertThat(SisScheduleNormalizer.parseClock("nope")).isNull()
    }
}

class SemesterTest {

    private val semester = Semester(firstWeekMonday = LocalDate.of(2026, 9, 7))

    @Test
    fun `week index is 1 based`() {
        assertThat(semester.weekIndexOf(LocalDate.of(2026, 9, 7))).isEqualTo(1)
        assertThat(semester.weekIndexOf(LocalDate.of(2026, 9, 13))).isEqualTo(1) // Sunday
        assertThat(semester.weekIndexOf(LocalDate.of(2026, 9, 14))).isEqualTo(2)
    }

    @Test
    fun `dates of week returns monday to sunday`() {
        val dates = semester.datesOfWeek(2)
        assertThat(dates).hasSize(7)
        assertThat(dates.first()).isEqualTo(LocalDate.of(2026, 9, 14))
        assertThat(dates.last()).isEqualTo(LocalDate.of(2026, 9, 20))
    }

    @Test
    fun `unanchored semester returns null week`() {
        assertThat(Semester().weekIndexOf(LocalDate.now())).isNull()
    }

    @Test
    fun `term display names`() {
        assertThat(Semester.termDisplayName("3")).isEqualTo("第一学期")
        assertThat(Semester.termDisplayName("12")).isEqualTo("第二学期")
        assertThat(Semester.termDisplayName("16")).isEqualTo("第三学期")
        assertThat(Semester.termDisplayName("99")).isNull()
    }
}
