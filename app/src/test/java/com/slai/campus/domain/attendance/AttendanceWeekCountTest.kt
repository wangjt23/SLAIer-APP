package com.slai.campus.domain.attendance

import com.google.common.truth.Truth.assertThat
import com.slai.campus.feature.attendance.AttendanceUiState
import org.junit.Test
import java.time.LocalDate

/**
 * 学院口径：**一周 7 天里任意 5 天**打卡即可，工作日缺的那天可以用周末补；一周超过 5 天的合格日不再累加。
 *
 * 这里的输入全部是「学校判定合格 / 不合格」（`isQual`），App 不推断任何一天是否合格 ——
 * 它只负责按上面的记账规则折算，并让超出部分在界面上有据可查。
 */
class AttendanceWeekCountTest {

    private val monday = LocalDate.of(2026, 9, 7)
    private val tuesday = monday.plusDays(1)
    private val wednesday = monday.plusDays(2)
    private val thursday = monday.plusDays(3)
    private val friday = monday.plusDays(4)
    private val saturday = monday.plusDays(5)
    private val sunday = monday.plusDays(6)

    private fun day(date: LocalDate, qualified: Boolean?): AttendanceRecord =
        AttendanceRecord(date = date, qualified = qualified)

    private fun week(vararg records: AttendanceRecord) = AttendanceWeek(
        label = "第2周 (2026-09-07 至 2026-09-13)",
        range = "2026-09-07,2026-09-13",
        records = records.toList()
    )

    /** 周一到周日全来了：只有前 5 天计入，周六周日即使学校判合格也不累加。 */
    @Test
    fun `a full week counts only the first five qualified days`() {
        val week = week(
            day(monday, true), day(tuesday, true), day(wednesday, true), day(thursday, true),
            day(friday, true), day(saturday, true), day(sunday, true)
        )

        assertThat(week.qualifiedCount).isEqualTo(7)
        assertThat(week.countedCount).isEqualTo(5)
        assertThat(week.overflowDates).containsExactly(saturday, sunday)
        assertThat(week.countedDates).containsExactly(monday, tuesday, wednesday, thursday, friday)
    }

    /** 周一没来、周二到周五都来、周六来补：周六算，周日再来就不算了。 */
    @Test
    fun `weekend makes up for a missed workday`() {
        val week = week(
            day(monday, false), day(tuesday, true), day(wednesday, true), day(thursday, true),
            day(friday, true), day(saturday, true), day(sunday, true)
        )

        assertThat(week.countedCount).isEqualTo(5)
        assertThat(week.countedDates).containsExactly(tuesday, wednesday, thursday, friday, saturday)
        assertThat(week.overflowDates).containsExactly(sunday)
    }

    /** 不足 5 天时按实际算，没有"未完成"以外的含义。 */
    @Test
    fun `a short week counts what actually qualified`() {
        val week = week(
            day(monday, true), day(tuesday, true), day(wednesday, true),
            day(thursday, false), day(friday, false), day(saturday, null), day(sunday, null)
        )

        assertThat(week.countedCount).isEqualTo(3)
        assertThat(week.overflowDates).isEmpty()
    }

    /** 顺序不能影响结果：学校返回的周内顺序不保证。 */
    @Test
    fun `records are counted in date order regardless of input order`() {
        val week = week(
            day(sunday, true), day(saturday, true), day(friday, true),
            day(monday, true), day(thursday, true), day(tuesday, true), day(wednesday, true)
        )

        // 7 天全合格，按日期顺序取前 5 天（周一~周五），周末两天被挤出。
        assertThat(week.countedDates).containsExactly(monday, tuesday, wednesday, thursday, friday)
        assertThat(week.overflowDates).containsExactly(saturday, sunday)
    }

    /** 跨月的那一周（8/31 属于第 1 周）照常折算。 */
    @Test
    fun `a week that starts in the previous month still works`() {
        val week = AttendanceWeek(
            label = "第1周 (2026-08-31 至 2026-09-06)",
            range = "2026-08-31,2026-09-06",
            records = listOf(
                day(LocalDate.of(2026, 8, 31), true),
                day(LocalDate.of(2026, 9, 1), true),
                day(LocalDate.of(2026, 9, 2), true),
                day(LocalDate.of(2026, 9, 3), true),
                day(LocalDate.of(2026, 9, 4), true),
                day(LocalDate.of(2026, 9, 5), true)
            )
        )

        assertThat(week.countedCount).isEqualTo(5)
        assertThat(week.overflowDates).containsExactly(LocalDate.of(2026, 9, 5))
    }

    /** 界面要能指出"这一天为什么不算"。 */
    @Test
    fun `ui state flags the days that fell outside the weekly cap`() {
        val month = AttendanceMonth(
            month = "2026-09",
            weeks = listOf(
                week(
                    day(monday, false), day(tuesday, true), day(wednesday, true), day(thursday, true),
                    day(friday, true), day(saturday, true), day(sunday, true)
                )
            )
        )
        val state = AttendanceUiState(month = "2026-09", monthData = month)

        assertThat(state.isNotCounted(sunday)).isTrue()
        assertThat(state.isNotCounted(saturday)).isFalse()
        assertThat(state.isNotCounted(monday)).isFalse()
    }
}
