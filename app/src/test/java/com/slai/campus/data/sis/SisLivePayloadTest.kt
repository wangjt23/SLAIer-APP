package com.slai.campus.data.sis

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.domain.schedule.ScheduleSource
import com.slai.campus.domain.schedule.Semester
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Regression tests built from a **real capture** of the live 深圳河套学院 deployment
 * (2026-09-09, real student login, request/response recorded via the recon browser).
 *
 * The first implementation failed against this data for three reasons, all pinned down here:
 *  1. the endpoint/body were guessed (`xskbcx` + `kzlx=ck`) instead of `kbcx` + `localeKey/xnm/xqm/zs`;
 *  2. `sxbj` was treated as an odd/even-week flag, but this deployment sets `sxbj=1` on *every*
 *     course — including plain `1-14周` ones — which silently dropped half the timetable;
 *  3. `sjkList` is empty, so the period times must come from `xskbcx_cxRjc.html`.
 *
 * The payload below is the real one with names replaced.
 */
class SisLivePayloadTest {

    /** 9 periods, 3 per 时段 — exactly what xskbcx_cxRjc.html returned. */
    private val periodTimes = listOf(
        LocalTime.of(9, 30) to LocalTime.of(10, 15),
        LocalTime.of(10, 30) to LocalTime.of(11, 15),
        LocalTime.of(11, 30) to LocalTime.of(12, 15),
        LocalTime.of(14, 30) to LocalTime.of(15, 15),
        LocalTime.of(15, 30) to LocalTime.of(16, 15),
        LocalTime.of(16, 30) to LocalTime.of(17, 15),
        LocalTime.of(18, 30) to LocalTime.of(19, 15),
        LocalTime.of(19, 30) to LocalTime.of(20, 15),
        LocalTime.of(20, 30) to LocalTime.of(21, 15)
    )

    private val semester = Semester(
        academicYear = "2026",
        termCode = "3",
        displayName = "第一学期",
        firstWeekMonday = LocalDate.of(2026, 9, 7) // 2026 年 9 月第一个星期一
    )

    private fun row(
        name: String,
        teacher: String,
        room: String,
        weekday: String,
        periods: String,
        code: String,
        mask: String = "16383"
    ) = """
        {
          "cd_id":"000002","cdmc":"$room","cxbj":"0",
          "date":"二○二六年九月九日","dateDigit":"2026年9月9日","day":"9",
          "jc":"${periods}节","jcor":"$periods","jcs":"$periods",
          "kch_id":"$code","kcmc":"$name","jxbmc":"$name-0001",
          "oldjc":"7","oldzc":"$mask","sxbj":"1",
          "xf":"3.0","xkbz":"无","xm":"$teacher",
          "xnm":"2026","xqj":"$weekday","xqjmc":"星期一",
          "xqm":"3","xqmc":"深圳校区",
          "zcd":"1-14周","zcmc":"教授","zhxs":"3."
        }
    """.trimIndent()

    private val payload = """
        {
          "kblx":7,"xqbzxxszList":[],"sjkList":[],"xkkg":true,
          "xqjmcMap":{"1":"星期一","2":"星期二","3":"星期三","4":"星期四","5":"星期五","6":"星期六","7":"星期日"},
          "xsxx":{"XNMC":"2026-2027","XQMMC":"1","XM":"学生","XQM":"3","XNM":"2026","KCMS":6},
          "kbList":[
            ${row("高等矩阵计算", "教师甲", "B401", "1", "1-3", "MF2602")},
            ${row("最优化方法与原理", "教师乙,教师丙", "B401", "1", "4-6", "MF2503")},
            ${row("人工智能基础设施", "教师丁,Teacher E", "B401", "2", "7-9", "MF2502")},
            ${row("深度表征学习原理", "教师己", "B401", "3", "1-3", "ME2501")},
            ${row("中国文化研究", "教师庚", "B401", "4", "7-8", "GE2601")},
            ${row("强化学习", "教师辛,Teacher I", "B411", "5", "1-3", "ME2502")}
          ]
        }
    """.trimIndent()

    private fun parse() = SisScheduleParser.parse(
        body = payload,
        semester = semester,
        source = ScheduleSource.SIS_NATIVE,
        accountHash = "acct",
        periodTimes = periodTimes
    )

    @Test
    fun `parses the live payload`() {
        val result = parse()
        assertThat(result).isInstanceOf(RemoteResult.Success::class.java)
        val parsed = (result as RemoteResult.Success).data
        assertThat(parsed.rawItemCount).isEqualTo(6)
        assertThat(parsed.skippedRows).isEqualTo(0)
    }

    @Test
    fun `every week is produced, not just odd ones`() {
        // The bug: sxbj=1 was read as "odd weeks", which would yield 7 occurrences per course
        // instead of 14. This is the assertion that would have caught it.
        val parsed = (parse() as RemoteResult.Success).data
        assertThat(parsed.occurrences).hasSize(6 * 14)

        val firstCourse = parsed.occurrences.filter { it.courseName == "高等矩阵计算" }
        assertThat(firstCourse).hasSize(14)
        assertThat(firstCourse.map { it.weekIndex }).containsExactlyElementsIn(1..14)
    }

    @Test
    fun `week bitmask matches the week text`() {
        val parsed = (parse() as RemoteResult.Success).data
        val course = parsed.occurrences.first { it.courseName == "高等矩阵计算" }
        assertThat(course.date).isEqualTo(LocalDate.of(2026, 9, 7)) // week 1, Monday
        val last = parsed.occurrences.filter { it.courseName == "高等矩阵计算" }.last()
        assertThat(last.date).isEqualTo(LocalDate.of(2026, 12, 7)) // week 14, Monday
    }

    @Test
    fun `period times come from the school's own table`() {
        val parsed = (parse() as RemoteResult.Success).data

        val morning = parsed.occurrences.first { it.courseName == "高等矩阵计算" }
        assertThat(morning.startTime).isEqualTo(LocalTime.of(9, 30))
        assertThat(morning.endTime).isEqualTo(LocalTime.of(12, 15))

        val afternoon = parsed.occurrences.first { it.courseName == "最优化方法与原理" }
        assertThat(afternoon.startTime).isEqualTo(LocalTime.of(14, 30))
        assertThat(afternoon.endTime).isEqualTo(LocalTime.of(17, 15))

        val evening = parsed.occurrences.first { it.courseName == "人工智能基础设施" }
        assertThat(evening.startTime).isEqualTo(LocalTime.of(18, 30))
        assertThat(evening.endTime).isEqualTo(LocalTime.of(21, 15))

        val short = parsed.occurrences.first { it.courseName == "中国文化研究" }
        assertThat(short.startTime).isEqualTo(LocalTime.of(18, 30))
        assertThat(short.endTime).isEqualTo(LocalTime.of(20, 15))
    }

    @Test
    fun `course code, class name, teacher and room are mapped`() {
        val parsed = (parse() as RemoteResult.Success).data
        val course = parsed.occurrences.first { it.courseName == "强化学习" }
        assertThat(course.teacher).isEqualTo("教师辛,Teacher I")
        assertThat(course.location).isEqualTo("B411")
        assertThat(course.campus).isEqualTo("深圳校区")
        assertThat(course.courseCode).isEqualTo("ME2502")
        assertThat(course.teachingClass).isEqualTo("强化学习-0001")
    }

    @Test
    fun `monday has two classes and tuesday has one`() {
        val parsed = (parse() as RemoteResult.Success).data
        val week1Monday = parsed.occurrences.filter { it.date == LocalDate.of(2026, 9, 7) }
        val week1Tuesday = parsed.occurrences.filter { it.date == LocalDate.of(2026, 9, 8) }
        assertThat(week1Monday.map { it.courseName })
            .containsExactly("高等矩阵计算", "最优化方法与原理")
        assertThat(week1Tuesday.map { it.courseName }).containsExactly("人工智能基础设施")
    }

    @Test
    fun `bitmask expansion handles gaps`() {
        // 1..4 plus 6..8 -> "1-4,6-8"
        val mask = 0b00011101111L // bits 0,1,2,3,5,6,7
        val ranges = SisScheduleNormalizer.weeksFromBitmask(mask)
        assertThat(ranges.map { it.start to it.end }).containsExactly(1 to 4, 6 to 8)
    }

    @Test
    fun `bitmask expansion of the real value`() {
        val ranges = SisScheduleNormalizer.weeksFromBitmask(16383L)
        assertThat(ranges).hasSize(1)
        assertThat(ranges.first().start).isEqualTo(1)
        assertThat(ranges.first().end).isEqualTo(14)
    }

    @Test
    fun `sxbj is never used as a parity flag`() {
        // Both rows carry sxbj="1". Only the week bitmask differs, and it decides.
        val oddMask = "5461"   // bits 0,2,4,6,8,10,12 -> weeks 1,3,5,7,9,11,13
        val odd = row("单周课", "师", "A", "1", "1-2", "X1", mask = oddMask)
        val every = row("每周课", "师", "A", "1", "1-2", "X2", mask = "16383")
        val body = """{"kbList":[$odd,$every],"xqjmcMap":{"1":"星期一"}}"""
        val parsed = SisScheduleParser.parse(
            body, semester, ScheduleSource.SIS_NATIVE, "acct", periodTimes
        ) as RemoteResult.Success
        assertThat(parsed.data.occurrences.count { it.courseName == "单周课" }).isEqualTo(7)
        assertThat(parsed.data.occurrences.count { it.courseName == "每周课" }).isEqualTo(14)
        assertThat(
            parsed.data.occurrences.filter { it.courseName == "单周课" }.map { it.weekIndex }
        ).containsExactly(1, 3, 5, 7, 9, 11, 13)
    }

    @Test
    fun `week text is used when the bitmask is absent`() {
        val noMask = row("单周课", "师", "A", "1", "1-2", "X1")
            .replace("\"oldzc\":\"16383\"", "\"oldzc\":\"\"")
            .replace("\"zcd\":\"1-14周\"", "\"zcd\":\"1-14周(单)\"")
        val body = """{"kbList":[$noMask],"xqjmcMap":{"1":"星期一"}}"""
        val parsed = SisScheduleParser.parse(
            body, semester, ScheduleSource.SIS_NATIVE, "acct", periodTimes
        ) as RemoteResult.Success
        assertThat(parsed.data.occurrences).hasSize(7)
    }

    @Test
    fun `week one anchor matches the academic-affairs system week counter`() {
        // 教务系统 2026-09-09 返回 {"year":"2026-2027","semester":"1","week":"1"}，
        // 即本周是第 1 周 → 第 1 周星期一 = 2026-09-07。
        assertThat(SemesterCalendarResolver.estimate("2026", SisConfig.Terms.FIRST))
            .isEqualTo(LocalDate.of(2026, 9, 7))
        assertThat(SemesterCalendarResolver.fromObservedWeek(1, LocalDate.of(2026, 9, 9)))
            .isEqualTo(LocalDate.of(2026, 9, 7))
        assertThat(SemesterCalendarResolver.fromObservedWeek(2, LocalDate.of(2026, 9, 9)))
            .isEqualTo(LocalDate.of(2026, 8, 31))
    }

    @Test
    fun `parses the real currentSemester payload`() {
        val term = parseServerTerm(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"year":"2026-2027","semester":"1","week":"1"}""")
        )
        assertThat(term).isNotNull()
        assertThat(term!!.xnm).isEqualTo("2026")
        assertThat(term.xqm).isEqualTo(SisConfig.Terms.FIRST)
        assertThat(term.week).isEqualTo(1)

        // 第二 / 第三学期
        assertThat(
            parseServerTerm(
                kotlinx.serialization.json.Json.parseToJsonElement("""{"year":"2026-2027","semester":"2","week":"9"}""")
            )!!.xqm
        ).isEqualTo(SisConfig.Terms.SECOND)
        assertThat(
            parseServerTerm(
                kotlinx.serialization.json.Json.parseToJsonElement("""{"year":"2026-2027","semester":"3"}""")
            )!!.xqm
        ).isEqualTo(SisConfig.Terms.THIRD)

        // 认不出就返回 null，不要瞎猜
        assertThat(parseServerTerm(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"foo":"bar"}""")
        )).isNull()
    }

    @Test
    fun `login page detection is strict`() {
        // 首页可能包含指向 login_slogin.html 的「重新登录」链接，不能因此判定会话过期。
        val home = "<html><body><a href='/yjsxt/xtgl/login_slogin.html'>重新登录</a></body></html>"
        assertThat(SisScheduleParser.looksLikeLoginPage(home)).isFalse()

        val realLogin = "<html><title>深圳河套学院-教务管理系统</title>" +
            "<form action='/yjsxt/xtgl/login_slogin.html'><input name=\"yhm\"><input name=\"mm\"></form></html>"
        assertThat(SisScheduleParser.looksLikeLoginPage(realLogin)).isTrue()
    }

    @Test
    fun `term estimate matches the live term`() {
        val (year, term) = SemesterCalendarResolver.estimateTermFromDate(LocalDate.of(2026, 9, 9))
        assertThat(year).isEqualTo("2026")
        assertThat(term).isEqualTo(SisConfig.Terms.FIRST)
    }
}
