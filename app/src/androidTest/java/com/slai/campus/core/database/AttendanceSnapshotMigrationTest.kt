package com.slai.campus.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Real SQLite/Room migration plus overlapping teaching-week snapshots, without production data. */
@RunWith(AndroidJUnit4::class)
class AttendanceSnapshotMigrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val day = LocalDate.of(2026, 10, 1)

    @Test fun version3MigrationPreservesAllCachedTablesAndAttendanceFields() = runBlocking {
        withDatabase(migrateVersion3 = true) { database ->
            // Opening through Room runs both migrations and validates the complete schema.
            val migrated = database.attendanceDao().observeMonth("account", "2026-09").first().single()
            assertEquals(record("2026-09", 100, 600), migrated)
            assertEquals("other", database.attendanceDao().observeMonth("other", "2026-09").first().single().accountHash)
            val sqlite = database.openHelper.writableDatabase
            assertEquals(5, sqlite.version)
            val retainedValues = mapOf(
                "course_occurrence" to "SELECT courseName FROM course_occurrence WHERE id='course'",
                "sync_meta" to "SELECT lastError FROM sync_meta WHERE accountHash='account'",
                "attendance_meta" to "SELECT stats FROM attendance_meta WHERE accountHash='account'",
                "attendance_punch" to "SELECT channel FROM attendance_punch WHERE id='punch'"
            )
            val expected = mapOf("course_occurrence" to "course-name", "sync_meta" to "saved-note",
                "attendance_meta" to "days=1", "attendance_punch" to "saved-channel")
            retainedValues.forEach { (table, query) ->
                sqlite.query(query).use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(expected[table], cursor.getString(0))
                }
            }
            val primaryKey = mutableMapOf<Int, String>()
            sqlite.query("PRAGMA table_info(attendance_record)").use { cursor ->
                assertEquals(17, cursor.count)
                while (cursor.moveToNext()) {
                    val position = cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                    if (position > 0) primaryKey[position] = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                }
            }
            assertEquals(listOf("accountHash", "month", "date"), primaryKey.toSortedMap().values.toList())
            val indexColumns = mutableListOf<String>()
            sqlite.query("PRAGMA index_info(index_attendance_record_accountHash_month)").use { cursor ->
                while (cursor.moveToNext()) indexColumns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertEquals(listOf("accountHash", "month"), indexColumns)
            database.attendanceDao().replaceMonth("account", "2026-10", listOf(record("2026-10", 200, 300)))
            assertEquals(1, database.attendanceDao().countMonth("account", "2026-09"))
            assertEquals(1, database.attendanceDao().countMonth("account", "2026-10"))
        }
    }

    @Test fun version4MigrationKeepsOldStatsAndRecordsAndStartsWithUnknownSchoolPeriod() = runBlocking {
        withDatabase(migrateVersion4 = true) { database ->
            assertEquals(5, database.openHelper.writableDatabase.version)
            assertEquals(record("2026-09", 100, 600), database.attendanceDao().observeMonth("account", "2026-09").first().single())
            val original = database.attendanceMetaDao().all("account").single()
            assertEquals("days=1", original.stats)
            assertEquals(null, original.periodWeeks)
            assertEquals(null, original.periodUpdatedAt)
            database.attendanceMetaDao().upsert(original.copy(
                periodWeeks = """[{"range":"2026-08-31,2026-09-06","label":"第1周"}]""",
                periodUpdatedAt = 1234
            ))
            val read = database.attendanceMetaDao().observeAll("account").first().single()
            assertEquals(original.stats, read.stats)
            assertEquals(1234L, read.periodUpdatedAt)
            assertEquals(true, read.periodWeeks?.contains("2026-08-31"))
            assertEquals(emptyList<AttendanceMetaEntity>(), database.attendanceMetaDao().observeAll("other").first())
            assertEquals(1, database.attendanceDao().countMonth("other", "2026-09"))
        }
    }

    @Test fun adjacentMonthSnapshotsKeepSameDayAndReplacingOneNeverDeletesOther() = runBlocking {
        withDatabase { database ->
            val dao = database.attendanceDao()
            val september = record("2026-09", 10, 100)
            val october = record("2026-10", 20, 200)
            dao.replaceMonth("account", "2026-09", listOf(september))
            dao.replaceMonth("account", "2026-10", listOf(october))
            assertEquals(listOf(september), dao.observeMonth("account", "2026-09").first())
            assertEquals(listOf(october), dao.observeMonth("account", "2026-10").first())
            assertEquals(october, dao.observeDay("account", day).first())

            val refreshedSeptember = september.copy(updatedAt = 30, durationMinutes = 250)
            dao.replaceMonth("account", "2026-09", listOf(refreshedSeptember))
            assertEquals(refreshedSeptember, dao.observeDay("account", day).first())
            assertEquals(listOf(october), dao.observeMonth("account", "2026-10").first())

            dao.replaceMonth("account", "2026-09", emptyList())
            assertEquals(october, dao.observeDay("account", day).first())
            assertEquals(listOf(october), dao.observeMonth("account", "2026-10").first())
            assertEquals(0, dao.countMonth("account", "2026-09"))
        }
    }

    @Test fun sameTimestampUsesLatestMonthAndAccountPartitionsRemainIsolated() = runBlocking {
        withDatabase { database ->
            val dao = database.attendanceDao()
            val september = record("2026-09", 10, 100)
            val october = record("2026-10", 10, 200)
            dao.insertAll(listOf(october, september, september.copy(accountHash = "other", updatedAt = 999)))
            assertEquals(october, dao.observeDay("account", day).first())
            assertEquals("other", dao.observeDay("other", day).first()?.accountHash)
            dao.replaceMonth("account", "2026-10", emptyList())
            assertEquals(september, dao.observeDay("account", day).first())
            assertEquals(1, dao.countMonth("other", "2026-09"))
        }
    }

    private suspend fun withDatabase(migrateVersion3: Boolean = false, migrateVersion4: Boolean = false, block: suspend (AppDatabase) -> Unit) {
        val name = "attendance-test-${UUID.randomUUID()}.db"
        if (migrateVersion3 || migrateVersion4) createOldVersion(name, if (migrateVersion4) 4 else 3)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
            .build()
        try { block(database) } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun record(month: String, updatedAt: Long, minutes: Int) = AttendanceRecordEntity(
        accountHash = "account", date = day, month = month, weekDay = "星期四", dayType = "教学日",
        isHoliday = "否", firstSwipe = LocalTime.of(8, 0), lastSwipe = LocalTime.of(18, 0),
        enterCount = 2, exitCount = 2, durationText = "10 小时", durationMinutes = minutes,
        qualified = true, leave = false, appeal = true, swipes = "[]", updatedAt = updatedAt
    )

    /** Independent schema fixture copied from the published v3 Room schema, not migration SQL. */
    private fun createOldVersion(name: String, version: Int) {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE course_occurrence (id TEXT NOT NULL PRIMARY KEY, accountHash TEXT NOT NULL, courseName TEXT NOT NULL, teacher TEXT, location TEXT, date INTEGER NOT NULL, startTime INTEGER NOT NULL, endTime INTEGER NOT NULL, source TEXT NOT NULL, weekIndex INTEGER, periodStart INTEGER, periodEnd INTEGER, courseCode TEXT, teachingClass TEXT, campus TEXT, updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX index_course_occurrence_accountHash_date ON course_occurrence(accountHash,date)")
            db.execSQL("CREATE INDEX index_course_occurrence_accountHash_updatedAt ON course_occurrence(accountHash,updatedAt)")
            db.execSQL("CREATE TABLE sync_meta (accountHash TEXT NOT NULL, system TEXT NOT NULL, lastSuccessAt INTEGER, lastAttemptAt INTEGER, parserVersion TEXT, lastError TEXT, integrationMode TEXT, semester_first_week_monday TEXT, PRIMARY KEY(accountHash,system))")
            val primaryKey = if (version == 4) "accountHash,month,date" else "accountHash,date"
            db.execSQL("CREATE TABLE attendance_record (accountHash TEXT NOT NULL, date INTEGER NOT NULL, month TEXT NOT NULL, weekDay TEXT, dayType TEXT, isHoliday TEXT, firstSwipe INTEGER, lastSwipe INTEGER, enterCount INTEGER, exitCount INTEGER, durationText TEXT, durationMinutes INTEGER, qualified INTEGER, leave INTEGER, appeal INTEGER, swipes TEXT, updatedAt INTEGER NOT NULL, PRIMARY KEY($primaryKey))")
            db.execSQL("CREATE INDEX index_attendance_record_accountHash_month ON attendance_record(accountHash,month)")
            db.execSQL("CREATE TABLE attendance_meta (accountHash TEXT NOT NULL, month TEXT NOT NULL, lastSuccessAt INTEGER, lastAttemptAt INTEGER, lastError TEXT, stats TEXT, PRIMARY KEY(accountHash,month))")
            db.execSQL("CREATE TABLE attendance_punch (id TEXT NOT NULL PRIMARY KEY, accountHash TEXT NOT NULL, time TEXT NOT NULL, date INTEGER NOT NULL, direction TEXT NOT NULL, channel TEXT, place TEXT, openingType TEXT, result TEXT, updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX index_attendance_punch_accountHash_date ON attendance_punch(accountHash,date)")
            db.execSQL("CREATE INDEX index_attendance_punch_accountHash_time ON attendance_punch(accountHash,time)")
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES (42,'1dfd561f21ce20be40ea5c737a900a6e')")
            db.execSQL("INSERT INTO course_occurrence VALUES ('course','account','course-name',NULL,NULL,?,28800,32400,'SIS_NATIVE',1,1,2,NULL,NULL,NULL,100)", arrayOf(day.toEpochDay()))
            db.execSQL("INSERT INTO sync_meta VALUES ('account','sis',100,100,'v3','saved-note','NATIVE_API','2026-09-07')")
            db.execSQL("INSERT INTO attendance_meta VALUES ('account','2026-09',100,100,NULL,'days=1')")
            db.execSQL("INSERT INTO attendance_punch VALUES ('punch','account','2026-10-01T08:00',?,'IN','saved-channel','楼','人脸','成功',100)", arrayOf(day.toEpochDay()))
            listOf("account", "other").forEach { account ->
                db.execSQL("INSERT INTO attendance_record VALUES (?,?,'2026-09','星期四','教学日','否',28800,64800,2,2,'10 小时',600,1,0,1,'[]',100)", arrayOf(account, day.toEpochDay()))
            }
            db.version = version
        }
    }
}
