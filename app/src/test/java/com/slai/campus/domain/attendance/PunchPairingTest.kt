package com.slai.campus.domain.attendance

import com.google.common.truth.Truth.assertThat
import com.slai.campus.data.stu.StuPunchParser
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 用真实抓到的闸机流水做回归测试。
 *
 * 数据来自 2026-09-09 实机抓包（`/a/edu/acm/swipe/listData`），其中包含三个真实世界的坑：
 *  1. 相邻闸机同秒重复的 `出门`（东5-出 / 东6-出）；
 *  2. 没有对应 `进门` 的孤立 `出门`；
 *  3. 当天最后一次 `进门` 尚未出门（人还在馆内）。
 *
 * 另外，考勤汇总接口当天的「当日累计时长」恒为 0，所以今日时长只能由这些流水算出。
 */
class PunchPairingTest {

    private val day = LocalDate.of(2026, 9, 9)

    private fun punch(
        time: String,
        event: String,
        channel: String = "闸机-东5-入_门禁通道_1",
        place: String = "教学楼"
    ) =
        AttendancePunch(
            id = time + event + channel,
            time = LocalDateTime.parse("2026-09-09T$time"),
            direction = PunchDirection.fromChinese(event),
            channel = channel,
            place = place
        )

    /** 真实顺序（服务端按时间倒序返回，这里按时间升序给出）。 */
    private val realPunches = listOf(
        punch("08:20:00", "进门", "闸机-东4-入_门禁通道_1"),
        punch("12:01:18", "出门", "闸机-东6-出_门禁通道_1"),
        punch("12:27:19", "进门", "闸机-东6-入_门禁通道_1"),
        punch("15:26:24", "出门", "闸机-东5-出_门禁通道_1"),
        punch("15:27:56", "进门", "闸机-东6-入_门禁通道_1"),
        punch("15:27:59", "出门", "闸机-东5-出_门禁通道_1"),
        punch("15:27:59", "出门", "闸机-东6-出_门禁通道_1"), // 同秒重复
        punch("17:59:38", "出门", "闸机-东4-出_门禁通道_1"), // 孤立出门
        punch("18:42:26", "进门", "闸机-东5-入_门禁通道_1")  // 尚未出门
    )

    private fun daily(now: String = "2026-09-09T21:00:00") =
        PunchPairing.of(day, realPunches, LocalDateTime.parse(now))

    @Test
    fun `pairs the real punches into three closed sessions plus one open`() {
        val result = daily()
        assertThat(result.sessions).hasSize(4)
        assertThat(result.sessions[3].isOpen).isTrue()
        assertThat(result.sessions[0].from.toLocalTime()).isEqualTo(LocalTime.of(8, 20))
        assertThat(result.sessions[0].to!!.toLocalTime()).isEqualTo(LocalTime.of(12, 1, 18))
        assertThat(result.sessions[1].from.toLocalTime()).isEqualTo(LocalTime.of(12, 27, 19))
        assertThat(result.sessions[1].to!!.toLocalTime()).isEqualTo(LocalTime.of(15, 26, 24))
        assertThat(result.sessions[2].from.toLocalTime()).isEqualTo(LocalTime.of(15, 27, 56))
        assertThat(result.sessions[2].to!!.toLocalTime()).isEqualTo(LocalTime.of(15, 27, 59))
    }

    @Test
    fun `accumulates only the paired sessions plus the open one`() {
        // 3:41:18 (221) + 2:59:05 (179) + 0:00:03 (0) = 400 分钟；再加未闭合段 18:42:26 → 21:00 = 137
        val result = daily()
        assertThat(result.minutesAt(LocalDateTime.parse("2026-09-09T21:00:00"))).isEqualTo(400 + 137)
    }

    @Test
    fun `the open session grows with now`() {
        val at19 = daily("2026-09-09T19:00:00").minutesAt(LocalDateTime.parse("2026-09-09T19:00:00"))
        val at21 = daily("2026-09-09T21:00:00").minutesAt(LocalDateTime.parse("2026-09-09T21:00:00"))
        assertThat(at21 - at19).isEqualTo(120)
    }

    @Test
    fun `duplicate same-second exits are collapsed`() {
        val result = daily()
        assertThat(result.exitCount).isEqualTo(4) // 12:01, 15:26, 15:27(去重后 1 条), 17:59
        assertThat(result.enterCount).isEqualTo(4)
    }

    @Test
    fun `an exit without a matching entry is ignored`() {
        val orphan = PunchPairing.of(
            day,
            listOf(punch("09:00:00", "出门")),
            LocalDateTime.parse("2026-09-09T21:00:00")
        )
        assertThat(orphan.sessions).isEmpty()
        assertThat(orphan.minutesAt(LocalDateTime.parse("2026-09-09T21:00:00"))).isEqualTo(0)
    }

    @Test
    fun `still inside is reported`() {
        val at21 = LocalDateTime.parse("2026-09-09T21:00:00")
        assertThat(daily().currentlyInsideAt(at21)).isTrue()
        assertThat(PunchPairing.of(day, realPunches.dropLast(1), at21).currentlyInsideAt(at21)).isFalse()
    }

    // ---------------------------------------------------------------------------------------
    // 进了没刷出：次日 05:00 之后整段作废
    // ---------------------------------------------------------------------------------------

    /**
     * 学院规定：只进了馆、到第二天凌晨 5 点还没刷出去，这一次进门算无效（0 分）。
     *
     * 没有这条规则时，一次忘刷的出门会**每天继续涨**。实测反馈里 8/31 21:03 那次未闭合的进门
     * 一路累加到 9/10，整天显示成「240 小时 24 分」——学生看到的数字完全失去意义。
     */
    @Test
    fun `an unclosed entry expires at 5am the next day`() {
        val open = PunchSession(LocalDateTime.parse("2026-08-31T21:03:00"), null)

        // 当晚 23:00：还算在馆，正常累计。
        val sameNight = LocalDateTime.parse("2026-08-31T23:00:00")
        assertThat(open.isStillOpenAt(sameNight)).isTrue()
        assertThat(open.minutesAt(sameNight)).isEqualTo(117)

        // 次日 04:59：仍然有效（还没到 5 点）。
        val justBefore = LocalDateTime.parse("2026-09-01T04:59:00")
        assertThat(open.minutesAt(justBefore)).isEqualTo(476)

        // 次日 05:00 整：作废，0 分。
        assertThat(open.minutesAt(LocalDateTime.parse("2026-09-01T05:00:00"))).isEqualTo(0)
        // …而且之后一直是 0，不会再涨。
        assertThat(open.minutesAt(LocalDateTime.parse("2026-09-10T11:00:00"))).isEqualTo(0)
        assertThat(open.isStillOpenAt(LocalDateTime.parse("2026-09-01T05:00:00"))).isFalse()
        assertThat(open.isDiscardedAt(LocalDateTime.parse("2026-09-01T05:00:00"))).isTrue()
    }

    @Test
    fun `a discarded open entry does not drag the whole day up`() {
        val aug31 = LocalDate.of(2026, 8, 31)
        val punches = listOf(
            AttendancePunch("a", LocalDateTime.parse("2026-08-31T09:30:00"), PunchDirection.IN, place = "教学楼"),
            AttendancePunch("b", LocalDateTime.parse("2026-08-31T19:54:00"), PunchDirection.OUT, place = "教学楼"),
            // 21:03 进门后一直没有出门记录
            AttendancePunch("c", LocalDateTime.parse("2026-08-31T21:03:00"), PunchDirection.IN, place = "教学楼")
        )

        val now = LocalDateTime.parse("2026-09-10T11:04:00")
        val d = PunchPairing.of(aug31, punches, now)

        // 只剩 09:30 → 19:54 那一段：10 小时 24 分。
        assertThat(d.sessions).hasSize(2)
        assertThat(d.minutesAt(now)).isEqualTo(10 * 60 + 24)
        // 文案随语言变，所以两种都要断言 —— 之前测试跑在 JVM 默认语言上，写死中文会在英文机器上挂。
        assertThat(d.textAt(now, java.util.Locale.CHINA)).isEqualTo("10 小时 24 分")
        assertThat(d.textAt(now, java.util.Locale.ENGLISH)).isEqualTo("10h 24m")
        assertThat(d.currentlyInsideAt(now)).isFalse()
        assertThat(d.hasDiscardedSessionAt(now)).isTrue()
        assertThat(d.sessions.last().textAt(now)).isEqualTo("21:03 → 未刷卡出门（作废）")
    }

    /** 今天刚进门、还没出去的情况不能被误伤。 */
    @Test
    fun `an entry opened today still counts up to now`() {
        val today = LocalDate.of(2026, 9, 10)
        val punches = listOf(
            AttendancePunch("a", LocalDateTime.parse("2026-09-10T08:31:00"), PunchDirection.IN, place = "教学楼")
        )
        val now = LocalDateTime.parse("2026-09-10T11:04:00")
        val d = PunchPairing.of(today, punches, now)
        assertThat(d.minutesAt(now)).isEqualTo(2 * 60 + 33)
        assertThat(d.currentlyInsideAt(now)).isTrue()
        assertThat(d.hasDiscardedSessionAt(now)).isFalse()
    }

    @Test
    fun `two entries without an exit keep the earlier one`() {
        val doubled = PunchPairing.of(
            day,
            listOf(punch("09:00:00", "进门"), punch("09:10:00", "进门"), punch("12:00:00", "出门")),
            LocalDateTime.parse("2026-09-09T21:00:00")
        )
        assertThat(doubled.sessions).hasSize(1)
        assertThat(doubled.sessions.first().from.toLocalTime()).isEqualTo(LocalTime.of(9, 0))
        assertThat(doubled.minutesAt(LocalDateTime.parse("2026-09-09T21:00:00"))).isEqualTo(180)
    }

    @Test
    fun `text formatting matches the app style`() {
        val result = daily()
        assertThat(result.sessions[0].textAt(LocalDateTime.parse("2026-09-09T21:00:00")))
            .isEqualTo("08:20 → 12:01")
    }

    // ---- 解析 ----------------------------------------------------------------

    @Test
    fun `parses the real listData payload`() {
        val body = """
            {"code":0,"count":102,"msg":"","data":[
              {"id":"a","swipeTime":"2026-09-09 18:42:26","swipeDate":"2026-09-09","eventType":"进门",
               "channelName":"闸机-东5-入_门禁通道_1","swipeType":"教学楼",
               "openingType":"人脸合法开门","openingResult":"成功"},
              {"id":"b","swipeTime":"2026-09-09 17:59:38","swipeDate":"2026-09-09","eventType":"出门",
               "channelName":"闸机-东4-出_门禁通道_1","swipeType":"教学楼",
               "openingType":"人脸合法开门","openingResult":"成功"}
            ]}
        """.trimIndent()
        val result = StuPunchParser.parse(body)
        assertThat(result).isInstanceOf(com.slai.campus.core.common.RemoteResult.Success::class.java)
        val punches = (result as com.slai.campus.core.common.RemoteResult.Success).data
        assertThat(punches).hasSize(2)
        assertThat(punches[0].direction).isEqualTo(PunchDirection.IN)
        assertThat(punches[0].channel).isEqualTo("闸机-东5-入_门禁通道_1")
        assertThat(punches[0].place).isEqualTo("教学楼")
        assertThat(punches[1].direction).isEqualTo(PunchDirection.OUT)
    }

    @Test
    fun `missing data array is a schema change`() {
        assertThat(StuPunchParser.parse("""{"code":0}"""))
            .isInstanceOf(com.slai.campus.core.common.RemoteResult.SchemaChanged::class.java)
    }

    @Test
    fun `non-zero code is reported`() {
        val result = StuPunchParser.parse("""{"code":500,"msg":"error","data":[]}""")
        assertThat(result).isInstanceOf(com.slai.campus.core.common.RemoteResult.SchemaChanged::class.java)
    }

    @Test
    fun `empty data array is a valid empty result`() {
        val result = StuPunchParser.parse("""{"code":0,"count":0,"data":[]}""")
        assertThat(result).isInstanceOf(com.slai.campus.core.common.RemoteResult.Success::class.java)
        assertThat((result as com.slai.campus.core.common.RemoteResult.Success).data).isEmpty()
    }

    @Test
    fun `html login page is a schema change`() {
        assertThat(StuPunchParser.parse("<html>x</html>"))
            .isInstanceOf(com.slai.campus.core.common.RemoteResult.SchemaChanged::class.java)
    }

    @Test
    fun `time formats`() {
        assertThat(StuPunchParser.parseDateTime("2026-09-09 18:42:26"))
            .isEqualTo(LocalDateTime.of(2026, 9, 9, 18, 42, 26))
        assertThat(StuPunchParser.parseDateTime("2026-09-09 18:42"))
            .isEqualTo(LocalDateTime.of(2026, 9, 9, 18, 42))
        assertThat(StuPunchParser.parseDateTime("nope")).isNull()
    }

    // ---------------------------------------------------------------------------------------
    // 宿舍楼闸机必须排除
    // ---------------------------------------------------------------------------------------

    /**
     * 真实数据（recon10，2026-09-07）：那天有 8 条教学楼流水和 2 条宿舍楼流水。
     *
     * ```text
     * 只算教学楼 → 646 分 = 10:46，与学校 durationStr「10:46:35」一致
     * 连宿舍一起 → 668 分 = 11:08，比学校多 22 分钟
     * ```
     *
     * 早上从宿舍出门、晚上回宿舍，会被配对成一段并不在教室里的时间；而学院要求的
     * "工作日 6 小时"指的是教学楼，所以多算的 22 分钟足以把不合格算成合格。
     */
    @Test
    fun `dormitory doors are excluded from the accumulated time`() {
        val sep7 = LocalDate.of(2026, 9, 7)
        fun p(time: String, event: String, place: String) = AttendancePunch(
            id = time + event + place,
            time = LocalDateTime.parse("2026-09-07T$time"),
            direction = PunchDirection.fromChinese(event),
            channel = if (place == "教学楼") "闸机-东5-入_门禁通道_1" else "闸机-宿舍-入_1",
            place = place
        )

        val punches = listOf(
            p("07:58:00", "出门", "宿舍楼"),
            p("08:20:00", "进门", "教学楼"),
            p("12:05:00", "出门", "教学楼"),
            p("12:30:00", "进门", "教学楼"),
            p("17:30:00", "出门", "教学楼"),
            p("19:00:00", "进门", "教学楼"),
            p("22:10:00", "出门", "教学楼"),
            p("22:32:00", "进门", "宿舍楼")
        )

        val now = LocalDateTime.parse("2026-09-07T23:59:00")
        val teachingOnly = PunchPairing.of(sep7, punches, now)

        // 只有教学楼的 6 条被保留；宿舍那两条被丢掉。
        assertThat(teachingOnly.punches).hasSize(6)
        assertThat(teachingOnly.punches.map { it.place }).containsExactly(
            "教学楼", "教学楼", "教学楼", "教学楼", "教学楼", "教学楼"
        )
        assertThat(teachingOnly.sessions).hasSize(3)
        // 08:20-12:05 = 225, 12:30-17:30 = 300, 19:00-22:10 = 190
        assertThat(teachingOnly.minutesAt(now)).isEqualTo(715)
    }

    @Test
    fun `a null place is not filtered out`() {
        val unknown = AttendancePunch(
            id = "x",
            time = LocalDateTime.parse("2026-09-07T09:00:00"),
            direction = PunchDirection.IN,
            place = null
        )
        assertThat(PunchPairing.teachingBuildingOnly(listOf(unknown))).hasSize(1)
    }

    // ---------------------------------------------------------------------------------------
    // 时长文案随语言变化
    // ---------------------------------------------------------------------------------------

    @Test
    fun `duration text follows the locale`() {
        val zh = java.util.Locale.CHINA
        val en = java.util.Locale.ENGLISH

        assertThat(AttendanceRecord.formatMinutes(48, zh)).isEqualTo("48 分钟")
        assertThat(AttendanceRecord.formatMinutes(48, en)).isEqualTo("48m")

        assertThat(AttendanceRecord.formatMinutes(180, zh)).isEqualTo("3 小时")
        assertThat(AttendanceRecord.formatMinutes(180, en)).isEqualTo("3h")

        assertThat(AttendanceRecord.formatMinutes(372, zh)).isEqualTo("6 小时 12 分")
        assertThat(AttendanceRecord.formatMinutes(372, en)).isEqualTo("6h 12m")

        // 624 分 = 10 小时 24 分，就是 8/31 作废那一段之后剩下的数
        assertThat(AttendanceRecord.formatMinutes(624, zh)).isEqualTo("10 小时 24 分")
        assertThat(AttendanceRecord.formatMinutes(624, en)).isEqualTo("10h 24m")
    }
}
