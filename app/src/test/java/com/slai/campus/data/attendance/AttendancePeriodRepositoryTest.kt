package com.slai.campus.data.attendance

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import com.slai.campus.core.common.SchoolSystem
import com.slai.campus.core.common.TimeProvider
import com.slai.campus.core.database.AttendanceDao
import com.slai.campus.core.database.AttendanceMetaDao
import com.slai.campus.core.database.AttendanceMetaEntity
import com.slai.campus.core.database.AttendancePunchDao
import com.slai.campus.core.database.AttendancePunchEntity
import com.slai.campus.core.database.AttendanceRecordEntity
import com.slai.campus.core.network.NetworkMonitor
import com.slai.campus.core.session.SessionManager
import com.slai.campus.core.session.SessionState
import com.slai.campus.core.session.SessionStore
import com.slai.campus.data.stu.StuAttendanceDataSource
import com.slai.campus.data.stu.StuAttendancePeriodParser
import com.slai.campus.data.stu.StuPunchDataSource
import com.slai.campus.data.stu.StuRemoteDataSource
import com.slai.campus.domain.attendance.AttendancePeriodResult
import com.slai.campus.domain.attendance.AttendanceRefreshResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AttendancePeriodRepositoryTest {
    private val date = LocalDate.of(2026, 10, 30)
    private val now = Instant.parse("2026-10-30T20:23:00Z")
    private val october = weeks("2026-09-28", 4)
    private val november = weeks("2026-10-26", 5)

    @Test fun `current period switches before calendar november using school's full weeks`() = runTest {
        val fixture = fixture { month -> 200 to if (month == "2026-10") october else november }
        val result = fixture.repository.resolvePeriod(date) as AttendancePeriodResult.Found
        assertThat(result.period.month).isEqualTo("2026-11")
        assertThat(fixture.requested).containsExactly("2026-10", "2026-11").inOrder()
        val cache = fixture.metas.all("account")
        assertThat(cache).hasSize(2)
        assertThat(StuAttendancePeriodParser.decode(cache.first { it.month == "2026-11" }.periodWeeks, "2026-11")?.weeks).hasSize(5)
    }

    @Test fun `cached complete cycle survives repository recreation and avoids extra network`() = runTest {
        val fixture = fixture { month -> 200 to if (month == "2026-10") october else november }
        fixture.repository.resolvePeriod(date)
        fixture.requested.clear()
        val recreated = fixture.createRepository()
        assertThat((recreated.resolvePeriod(date) as AttendancePeriodResult.Found).period.month).isEqualTo("2026-11")
        assertThat(recreated.observePeriods().first().map { it.month }).containsExactly("2026-10", "2026-11")
        assertThat(fixture.requested).isEmpty()
    }

    @Test fun `bad or temporarily empty responses retain known cycle and existing statistics`() = runTest {
        var body = "malformed"
        val fixture = fixture { 200 to body }
        fixture.metas.upsert(cached("account", "2026-11", november, ageHours = 7).copy(stats = "totalValidPunches=4"))
        assertThat((fixture.repository.periodForMonth("2026-11") as AttendancePeriodResult.Found).period.start)
            .isEqualTo(LocalDate.of(2026, 10, 26))
        val original = fixture.metas.all("account").single()
        body = """{"success":true,"data":[]}"""
        assertThat(fixture.repository.periodForMonth("2026-11")).isInstanceOf(AttendancePeriodResult.Found::class.java)
        assertThat(fixture.metas.all("account").single()).isEqualTo(original)
        assertThat(fixture.recordWrites).isEmpty()
    }

    @Test fun `confirmed empty future cycle is cached briefly without inventing a month`() = runTest {
        val fixture = fixture { 200 to """{"success":true,"data":[]}""" }
        repeat(2) { assertThat(fixture.repository.periodForMonth("2026-12")).isInstanceOf(AttendancePeriodResult.Unavailable::class.java) }
        assertThat(fixture.requested).containsExactly("2026-12")
        assertThat(fixture.repository.observePeriods().first()).isEmpty()
    }

    @Test fun `overlapping cached periods are revalidated despite fresh TTL and corrected by school`() = runTest {
        val fixture = fixture { month -> 200 to if (month == "2026-10") october else november }
        fixture.metas.upsert(cached("account", "2026-10", weeks("2026-09-28", 5)))
        fixture.metas.upsert(cached("account", "2026-11", november))
        val result = fixture.repository.resolvePeriod(date) as AttendancePeriodResult.Found
        assertThat(result.period.month).isEqualTo("2026-11")
        assertThat(fixture.requested).containsExactly("2026-10", "2026-11").inOrder()
    }

    @Test fun `still overlapping fresh school ranges cannot silently select either month`() = runTest {
        val fixture = fixture { month -> 200 to if (month == "2026-10") weeks("2026-09-28", 5) else november }
        fixture.metas.upsert(cached("account", "2026-10", weeks("2026-09-28", 5)))
        fixture.metas.upsert(cached("account", "2026-11", november))
        val result = fixture.repository.resolvePeriod(date) as AttendancePeriodResult.Unavailable
        assertThat(result.reason).isInstanceOf(AttendanceRefreshResult.SchemaChanged::class.java)
        assertThat(fixture.requested).containsExactly("2026-10", "2026-11").inOrder()
    }

    @Test fun `corrected shorter cycle is not replaced by the disproved entry snapshot on neighbour failure`() = runTest {
        val fixture = fixture { month -> if (month == "2026-11") 200 to weeks("2026-10-26", 4) else 503 to "Unavailable" }
        fixture.metas.upsert(cached("account", "2026-11", november, ageHours = 7))
        val result = fixture.repository.resolvePeriod(LocalDate.of(2026, 11, 25))
        assertThat(result).isInstanceOf(AttendancePeriodResult.Unavailable::class.java)
        assertThat(StuAttendancePeriodParser.decode(fixture.metas.all("account").first { it.month == "2026-11" }.periodWeeks, "2026-11")?.period?.endInclusive)
            .isEqualTo(LocalDate.of(2026, 11, 22))
    }

    @Test fun `period metadata from another account is never read as current cache`() = runTest {
        val fixture = fixture { 200 to """{"success":true,"data":[]}""" }
        fixture.metas.upsert(cached("other", "2026-11", november))
        assertThat(fixture.repository.observePeriods().first()).isEmpty()
        assertThat(fixture.repository.periodForMonth("2026-11")).isInstanceOf(AttendancePeriodResult.Unavailable::class.java)
        assertThat(fixture.metas.all("other").single().periodWeeks).isNotNull()
        assertThat(fixture.requested).containsExactly("2026-11")
    }

    @Test fun `old period response after new login cannot write cache or authenticate new session`() = runTest {
        val fixture = fixture { 200 to november }
        fixture.beforeResponse = { fixture.manager.beginLogin(SchoolSystem.STU) }
        assertThat(fixture.repository.periodForMonth("2026-11"))
            .isEqualTo(AttendancePeriodResult.Unavailable(AttendanceRefreshResult.Superseded))
        assertThat(fixture.metas.all("account")).isEmpty()
        assertThat(fixture.manager.stateOf(SchoolSystem.STU)).isNotEqualTo(SessionState.AUTHENTICATED)
    }

    @Test fun `account change while reading cached periods invalidates the cache fast path`() = runTest {
        val fixture = fixture { error("Fresh cache needs no network") }
        fixture.metas.upsert(cached("account", "2026-11", november))
        fixture.metas.beforeRead = { fixture.manager.beginLogin(SchoolSystem.STU) }
        assertThat(fixture.repository.resolvePeriod(date))
            .isEqualTo(AttendancePeriodResult.Unavailable(AttendanceRefreshResult.Superseded))
    }

    @Test fun `account change while rereading periods after a lookup invalidates early success`() = runTest {
        val fixture = fixture { 200 to november }
        var reads = 0
        fixture.metas.beforeRead = { if (++reads == 3) fixture.manager.beginLogin(SchoolSystem.STU) }
        assertThat(fixture.repository.resolvePeriod(date))
            .isEqualTo(AttendancePeriodResult.Unavailable(AttendanceRefreshResult.Superseded))
    }

    @Test fun `week list does not change account identity or prove a pending login complete`() = runTest {
        val fixture = fixture { 200 to november }
        fixture.manager.beginLogin(SchoolSystem.STU)
        assertThat(fixture.repository.periodForMonth("2026-11")).isInstanceOf(AttendancePeriodResult.Found::class.java)
        assertThat(fixture.store.accountPending(SchoolSystem.STU)).isTrue()
        assertThat(fixture.store.current().stuAccountHash).isEqualTo("account")
        assertThat(fixture.manager.stateOf(SchoolSystem.STU)).isNotEqualTo(SessionState.AUTHENTICATED)
    }

    @Test fun `summary discovering longer period expands punches and shared completion scope`() = runTest {
        val fixture = fixture { 200 to november }
        fixture.metas.upsert(cached("account", "2026-11", weeks("2026-10-26", 4)))
        val weekArray = Json.parseToJsonElement(november).jsonObject.getValue("data")
        fixture.summaryBody = """{"success":true,"weeks":$weekArray,"data":{
            "week1":[{"date":"2026-10-26","durationStr":"00:00:00"}],
            "week2":[],"week3":[],"week4":[],"week5":[]},"stats":{}}"""
        val result = fixture.repository.refreshWithPunches("2026-11", LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 23))
        assertThat(result.isSuccess).isTrue()
        assertThat(fixture.punchRanges).contains(LocalDate.of(2026, 10, 25) to LocalDate.of(2026, 11, 30))
        val scope = fixture.repository.refreshUpdates.value.keys.single()
        assertThat(scope.from).isEqualTo(LocalDate.of(2026, 10, 25))
        assertThat(scope.to).isEqualTo(LocalDate.of(2026, 11, 30))
        assertThat((fixture.repository.periodForMonth("2026-11") as AttendancePeriodResult.Found).period.endInclusive)
            .isEqualTo(LocalDate.of(2026, 11, 29))
    }

    private fun weeks(start: String, count: Int): String {
        val monday = LocalDate.parse(start)
        return """{"success":true,"data":[${(0 until count).joinToString(",") { index ->
            val from = monday.plusWeeks(index.toLong())
            """{"range":"$from,${from.plusDays(6)}","label":"第${index + 1}周"}"""
        }}]}"""
    }

    private fun cached(owner: String, month: String, body: String, ageHours: Long = 0): AttendanceMetaEntity {
        val metadata = (StuAttendancePeriodParser.parse(body, month) as com.slai.campus.core.common.RemoteResult.Success).data
        return AttendanceMetaEntity(owner, month, null, null, null, null,
            StuAttendancePeriodParser.encode(metadata), now.toEpochMilli() - ageHours * 3_600_000)
    }

    private class Store : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class Metas : AttendanceMetaDao {
        val values = MutableStateFlow(emptyList<AttendanceMetaEntity>())
        var beforeRead: suspend () -> Unit = { }
        override fun observeAll(accountHash: String) = values.map { rows -> rows.filter { it.accountHash == accountHash } }
        override fun observe(accountHash: String, month: String) = observeAll(accountHash).map { it.firstOrNull { row -> row.month == month } }
        override suspend fun all(accountHash: String): List<AttendanceMetaEntity> {
            beforeRead()
            return values.value.filter { it.accountHash == accountHash }
        }
        override suspend fun upsert(meta: AttendanceMetaEntity) {
            values.value = values.value.filterNot { it.accountHash == meta.accountHash && it.month == meta.month } + meta
        }
    }

    private inner class Fixture(
        val scope: TestScope,
        route: (String) -> Pair<Int, String>
    ) {
        val requested = mutableListOf<String>()
        val punchRanges = mutableListOf<Pair<LocalDate, LocalDate>>()
        var summaryBody: String? = null
        var beforeResponse: suspend () -> Unit = { }
        val store = SessionStore(Store())
        val manager = SessionManager(store, emptySet(), scope.backgroundScope)
        val metas = Metas()
        val recordWrites = mutableListOf<AttendanceRecordEntity>()
        private val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        private val time = TimeProvider(Clock.fixed(now, ZoneId.of("Asia/Shanghai")))
        private val network = NetworkMonitor(ContextWrapper(null))
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val (code, body) = when (url.encodedPath.substringAfterLast('/')) {
                "weekGroupedByMonth" -> 200 to requireNotNull(summaryBody)
                "listData" -> {
                    val from = url.queryParameter("startTime").orEmpty()
                    val to = url.queryParameter("endTime").orEmpty()
                    if (from.isNotEmpty() && to.isNotEmpty()) punchRanges += LocalDate.parse(from) to LocalDate.parse(to)
                    200 to """{"code":0,"count":0,"data":[]}"""
                }
                else -> {
                    val month = requireNotNull(url.queryParameter("month"))
                    requested += month
                    route(month)
                }
            }
            runBlocking { beforeResponse() }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
                .body(body.toResponseBody()).build()
        }.build()
        private val records = object : AttendanceDao {
            override fun observeDay(accountHash: String, date: LocalDate) = flowOf<AttendanceRecordEntity?>(null)
            override fun observeMonth(accountHash: String, month: String) = flowOf(emptyList<AttendanceRecordEntity>())
            override suspend fun countMonth(accountHash: String, month: String) = 0
            override suspend fun deleteMonth(accountHash: String, month: String) = Unit
            override suspend fun insertAll(rows: List<AttendanceRecordEntity>) { recordWrites += rows }
        }
        private val punches = object : AttendancePunchDao {
            override fun observeRange(accountHash: String, from: LocalDate, to: LocalDate) = flowOf(emptyList<AttendancePunchEntity>())
            override suspend fun countRange(accountHash: String, from: LocalDate, to: LocalDate) = 0
            override suspend fun deleteRange(accountHash: String, from: LocalDate, to: LocalDate) = Unit
            override suspend fun insertAll(rows: List<AttendancePunchEntity>) = Unit
        }
        fun createRepository() = AttendanceRepositoryImpl(records, metas, punches,
            StuPunchDataSource(client, network, dispatcher), manager, store,
            StuAttendanceDataSource(client, network, time, dispatcher),
            StuRemoteDataSource(client, client, network, store, dispatcher), network, time, dispatcher)
        val repository = createRepository()
    }

    private suspend fun TestScope.fixture(route: (String) -> Pair<Int, String>): Fixture = Fixture(this, route).also {
        it.store.setAccountHash(SchoolSystem.STU, "account")
        runCurrent()
    }
}
