package com.slai.campus.data.stu

import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.RemoteResult
import com.slai.campus.domain.attendance.AttendancePunch
import com.slai.campus.domain.attendance.PunchDirection
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

class StuPunchPagesTest {
    private val day = LocalDate.of(2026, 10, 1)
    private fun punch(id: Int, date: LocalDate = day) = AttendancePunch(
        id = id.toString(), time = date.atTime(8, 0), direction = PunchDirection.IN,
        channel = null, place = null, openingType = null, result = null
    )

    @Test fun `more than 500 rows require every advertised page`() = runTest {
        val calls = mutableListOf<Int>()
        val result = readPunchPages { page ->
            calls += page
            val range = if (page == 1) 1..500 else 501..502
            RemoteResult.Success(StuPunchPage(range.map { punch(it) }, 502))
        }
        assertThat(calls).containsExactly(1, 2).inOrder()
        assertThat((result as RemoteResult.Success).data).hasSize(502)
    }

    @Test fun `server side page cap smaller than requested size still paginates by count`() = runTest {
        val result = readPunchPages { page ->
            RemoteResult.Success(StuPunchPage(listOf(punch(page)), 3))
        }
        assertThat((result as RemoteResult.Success).data.map { it.id }).containsExactly("1", "2", "3")
    }

    @Test fun `empty first page is accepted only when count is zero`() = runTest {
        assertThat(readPunchPages { RemoteResult.Success(StuPunchPage(emptyList(), 0)) })
            .isEqualTo(RemoteResult.Success(emptyList<AttendancePunch>()))
        assertThat(readPunchPages { RemoteResult.Success(StuPunchPage(emptyList(), 1)) })
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `empty later page is a failure rather than partial success`() = runTest {
        val result = readPunchPages { page ->
            RemoteResult.Success(StuPunchPage(if (page == 1) listOf(punch(1)) else emptyList(), 2))
        }
        assertThat(result).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `failure on later page never publishes the first page`() = runTest {
        val result = readPunchPages { page ->
            if (page == 1) RemoteResult.Success(StuPunchPage(listOf(punch(1)), 2))
            else RemoteResult.SessionExpired
        }
        assertThat(result).isEqualTo(RemoteResult.SessionExpired)
    }

    @Test fun `changing count is a failure and not a completed snapshot`() = runTest {
        val result = readPunchPages { page ->
            RemoteResult.Success(StuPunchPage(listOf(punch(page)), if (page == 1) 2 else 3))
        }
        assertThat(result).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `pagination that repeats the first page cannot loop or replace cache`() = runTest {
        var calls = 0
        val result = readPunchPages {
            calls++
            RemoteResult.Success(StuPunchPage(listOf(punch(1)), 2))
        }
        assertThat(calls).isEqualTo(2)
        assertThat(result).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `duplicate ids within a page are not accepted`() = runTest {
        val result = readPunchPages {
            RemoteResult.Success(StuPunchPage(listOf(punch(1), punch(1)), 2))
        }
        assertThat(result).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `more rows than count and page bound both fail closed`() = runTest {
        assertThat(readPunchPages { RemoteResult.Success(StuPunchPage(listOf(punch(1)), 0)) })
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
        assertThat(readPunchPages(maxPages = 1) { RemoteResult.Success(StuPunchPage(listOf(punch(1)), 2)) })
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `one malformed event invalidates the whole page`() {
        val result = StuPunchParser.parsePage("""{"code":0,"count":2,"data":[
            {"id":"1","swipeTime":"2026-10-01 08:00:00"},{"id":"2","swipeTime":"bad"}]}""")
        assertThat(result).isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `missing count cannot certify complete results`() {
        assertThat(StuPunchParser.parsePage("""{"code":0,"data":[]}"""))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
        assertThat(StuPunchParser.parsePage("""{"code":0,"count":-1,"data":[]}"""))
            .isInstanceOf(RemoteResult.SchemaChanged::class.java)
    }

    @Test fun `date filter fallback finds historical records after newer first page`() = runTest {
        val calls = mutableListOf<Pair<LocalDate?, LocalDate?>>()
        val result = readPunchRange(day, day) { start, end ->
            calls += start to end
            if (start != null) RemoteResult.Success(emptyList()) else readPunchPages { page ->
                RemoteResult.Success(StuPunchPage(
                    listOf(punch(page, if (page == 1) day.plusDays(1) else day)), 2
                ))
            }
        }
        assertThat(calls).containsExactly(day to day, null to null).inOrder()
        assertThat((result as RemoteResult.Success).data.map { it.id }).containsExactly("2")
    }

    @Test fun `failed unfiltered fallback cannot erase historical cache with empty success`() = runTest {
        val result = readPunchRange(day, day) { start, _ ->
            if (start != null) RemoteResult.Success(emptyList()) else readPunchPages { page ->
                if (page == 1) RemoteResult.Success(StuPunchPage(listOf(punch(1, day.plusDays(1))), 2))
                else RemoteResult.NetworkUnavailable("timeout")
            }
        }
        assertThat(result).isEqualTo(RemoteResult.NetworkUnavailable("timeout"))
    }

    @Test fun `successful filtered range needs no fallback and is filtered locally`() = runTest {
        var calls = 0
        val result = readPunchRange(day, day) { _, _ ->
            calls++
            RemoteResult.Success(listOf(punch(1), punch(2, day.plusDays(1))))
        }
        assertThat(calls).isEqualTo(1)
        assertThat((result as RemoteResult.Success).data.map { it.id }).containsExactly("1")
    }
}
