package com.slai.campus.data.stu

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Built from a real capture of 学生考勤统计查询 (2026-09-09).
 *
 * Request: `POST /a/edu/acm/swipe/weekGroupedByMonth`, body `startMonth=2026-09&cycleWeek=`.
 * The field names asserted here are exactly the ones the live server returned.
 */
class StuAttendanceParserTest {

    private val payload = """
        {
          "weeks": [
            {"range":"2026-08-31,2026-09-06","label":"第1周 (2026-08-31 至 2026-09-06)"},
            {"range":"2026-09-07,2026-09-13","label":"第2周 (2026-09-07 至 2026-09-13)"}
          ],
          "data": {
            "week1": [
              {"date":"2026-08-31","exitCount":0,"swipeTimes":"","isLeave":"否","userNo":"***",
               "isAppeal":"否","isHoliday":"非节假日","firstSwipe":"","userName":"***",
               "duration":37437,"dayType":"工作日","weekDay":"Monday","durationStr":"10:23:57",
               "enterCount":0,"lastSwipe":"","isQual":"是"},
              {"date":"2026-09-01","exitCount":1,"swipeTimes":"08:52,21:30","isLeave":"否","userNo":"***",
               "isAppeal":"否","isHoliday":"非节假日","firstSwipe":"08:52","userName":"***",
               "duration":44880,"dayType":"工作日","weekDay":"Tuesday","durationStr":"12:28:00",
               "enterCount":1,"lastSwipe":"21:30","isQual":"是"}
            ],
            "week2": [
              {"date":"2026-09-07","exitCount":1,"swipeTimes":"09:05,18:12","isLeave":"否","userNo":"***",
               "isAppeal":"否","isHoliday":"非节假日","firstSwipe":"09:05","userName":"***",
               "duration":32820,"dayType":"工作日","weekDay":"Monday","durationStr":"09:07:00",
               "enterCount":1,"lastSwipe":"18:12","isQual":"是"},
              {"date":"2026-09-08","exitCount":0,"swipeTimes":"","isLeave":"是","userNo":"***",
               "isAppeal":"否","isHoliday":"非节假日","firstSwipe":"","userName":"***",
               "duration":0,"dayType":"工作日","weekDay":"Tuesday","durationStr":"00:00:00",
               "enterCount":0,"lastSwipe":"","isQual":"否"}
            ]
          },
          "stats": {"totalDays":"4","qualifiedDays":"3"},
          "success": true
        }
    """.trimIndent()

    private fun parse() = StuAttendanceParser.parse(payload, "2026-09")

    @Test
    fun `parses the live payload`() {
        val result = parse()
        assertThat(result).isInstanceOf(RemoteResult.Success::class.java)
        val month = (result as RemoteResult.Success).data
        assertThat(month.month).isEqualTo("2026-09")
        assertThat(month.weeks).hasSize(2)
        assertThat(month.records).hasSize(4)
    }

    @Test
    fun `week labels and ranges are kept`() {
        val month = (parse() as RemoteResult.Success).data
        assertThat(month.weeks.first().label).contains("第1周")
        assertThat(month.weeks.first().shortLabel).isEqualTo("第1周")
        assertThat(month.weeks.first().start).isEqualTo(LocalDate.of(2026, 8, 31))
        assertThat(month.weeks.first().end).isEqualTo(LocalDate.of(2026, 9, 6))
    }

    @Test
    fun `swipe in and out times are parsed`() {
        val month = (parse() as RemoteResult.Success).data
        val day = month.recordFor(LocalDate.of(2026, 9, 1))!!
        assertThat(day.firstSwipe).isEqualTo(LocalTime.of(8, 52))
        assertThat(day.lastSwipe).isEqualTo(LocalTime.of(21, 30))
        assertThat(day.enterCount).isEqualTo(1)
        assertThat(day.exitCount).isEqualTo(1)
        assertThat(day.durationText).isEqualTo("12:28:00")
        assertThat(day.swipeRange).isEqualTo("08:52 → 21:30")
        assertThat(day.hasSwipes).isTrue()
    }

    @Test
    fun `duration seconds become minutes`() {
        val month = (parse() as RemoteResult.Success).data
        val day = month.recordFor(LocalDate.of(2026, 9, 7))!!
        assertThat(day.durationMinutes).isEqualTo(32820 / 60)
    }

    @Test
    fun `all swipe times are extracted when provided`() {
        val month = (parse() as RemoteResult.Success).data
        val day = month.recordFor(LocalDate.of(2026, 9, 1))!!
        assertThat(day.swipes).containsExactly(LocalTime.of(8, 52), LocalTime.of(21, 30))
    }

    @Test
    fun `days without swipes are still records`() {
        val month = (parse() as RemoteResult.Success).data
        val day = month.recordFor(LocalDate.of(2026, 8, 31))!!
        assertThat(day.hasSwipes).isFalse()
        assertThat(day.swipeRange).isEqualTo("无刷卡记录")
        assertThat(day.qualified).isTrue()
    }

    @Test
    fun `leave flag is parsed`() {
        val month = (parse() as RemoteResult.Success).data
        val leave = month.recordFor(LocalDate.of(2026, 9, 8))!!
        assertThat(leave.leave).isTrue()
        assertThat(leave.qualified).isFalse()
    }

    @Test
    fun `stats are surfaced`() {
        val month = (parse() as RemoteResult.Success).data
        assertThat(month.stats["totalDays"]).isEqualTo("4")
        assertThat(month.stats["qualifiedDays"]).isEqualTo("3")
    }

    @Test
    fun `missing weeks and data is a schema change`() {
        val result = StuAttendanceParser.parse("""{"success":true}""", "2026-09")
        assertThat(result).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test
    fun `html login page is a schema change`() {
        val result = StuAttendanceParser.parse("<html><body>login</body></html>", "2026-09")
        assertThat(result).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test
    fun `empty body is a schema change`() {
        assertThat(StuAttendanceParser.parse("", "2026-09"))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test
    fun `explicit failure flag is reported`() {
        val result = StuAttendanceParser.parse("""{"success":false,"message":"无权限"}""", "2026-09")
        assertThat(result).isInstanceOf(RemoteResult.SchemaChanged::class.java)
        assertThat((result as RemoteResult.SchemaChanged).reason).contains("无权限")
    }

    @Test
    fun `data-only payload still produces weeks`() {
        val body = """{"data":{"week1":[{"date":"2026-09-07","firstSwipe":"09:00","lastSwipe":"18:00","enterCount":1,"exitCount":1}]}}"""
        val month = (StuAttendanceParser.parse(body, "2026-09") as RemoteResult.Success).data
        assertThat(month.records).hasSize(1)
    }

    @Test
    fun `time parser handles several formats`() {
        assertThat(StuAttendanceParser.parseTime("08:52")).isEqualTo(LocalTime.of(8, 52))
        assertThat(StuAttendanceParser.parseTime("08:52:31")).isEqualTo(LocalTime.of(8, 52))
        assertThat(StuAttendanceParser.parseTime("2026-09-09 08:52")).isEqualTo(LocalTime.of(8, 52))
        assertThat(StuAttendanceParser.parseTime("")).isNull()
        assertThat(StuAttendanceParser.parseTime(null)).isNull()
    }

    @Test
    fun `month validation`() {
        assertThat(StuAttendanceDataSource.isValidMonth("2026-09")).isTrue()
        assertThat(StuAttendanceDataSource.isValidMonth("2026-13")).isFalse()
        assertThat(StuAttendanceDataSource.isValidMonth("2026-9")).isFalse()
        assertThat(StuAttendanceDataSource.isValidMonth("26-09")).isFalse()
    }
}
