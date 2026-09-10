package com.slai.campus.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface ScheduleDao {

    @Query(
        """
        SELECT * FROM course_occurrence
        WHERE accountHash = :accountHash AND date = :date
        ORDER BY startTime ASC, courseName ASC
        """
    )
    fun observeByDate(accountHash: String, date: LocalDate): Flow<List<CourseOccurrenceEntity>>

    @Query(
        """
        SELECT * FROM course_occurrence
        WHERE accountHash = :accountHash AND date BETWEEN :from AND :to
        ORDER BY date ASC, startTime ASC, courseName ASC
        """
    )
    fun observeRange(accountHash: String, from: LocalDate, to: LocalDate): Flow<List<CourseOccurrenceEntity>>

    @Query(
        """
        SELECT * FROM course_occurrence
        WHERE accountHash = :accountHash AND date BETWEEN :from AND :to
        ORDER BY date ASC, startTime ASC
        """
    )
    suspend fun rangeOnce(accountHash: String, from: LocalDate, to: LocalDate): List<CourseOccurrenceEntity>

    @Query("SELECT DISTINCT date FROM course_occurrence WHERE accountHash = :accountHash ORDER BY date ASC")
    fun observeDistinctDates(accountHash: String): Flow<List<LocalDate>>

    @Query("SELECT MIN(date) FROM course_occurrence WHERE accountHash = :accountHash")
    suspend fun earliestDate(accountHash: String): LocalDate?

    @Query("SELECT MAX(date) FROM course_occurrence WHERE accountHash = :accountHash")
    suspend fun latestDate(accountHash: String): LocalDate?

    @Query("SELECT COUNT(*) FROM course_occurrence WHERE accountHash = :accountHash")
    suspend fun count(accountHash: String): Int

    @Query("SELECT COUNT(*) FROM course_occurrence WHERE accountHash = :accountHash")
    fun observeCount(accountHash: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CourseOccurrenceEntity>)

    @Query("DELETE FROM course_occurrence WHERE accountHash = :accountHash")
    suspend fun deleteForAccount(accountHash: String)

    @Query("DELETE FROM course_occurrence WHERE accountHash = :accountHash AND id IN (:ids)")
    suspend fun deleteByIds(accountHash: String, ids: List<String>)

    /**
     * Atomic replacement.
     *
     * The plan forbids "delete everything, then fetch": if the fetch or the parse fails the user
     * would be left with an empty timetable. This method is only ever called with a payload that has
     * already passed full schema validation, inside a single transaction, so a crash mid-write
     * rolls back to the previous good data.
     */
    @Transaction
    suspend fun replaceAll(accountHash: String, rows: List<CourseOccurrenceEntity>) {
        deleteForAccount(accountHash)
        if (rows.isNotEmpty()) insertAll(rows)
    }

    /**
     * Replaces only the dates covered by [rows], leaving other dates untouched. Used by the
     * week-scoped WebView extraction path, which may only ever see one week at a time.
     */
    @Transaction
    suspend fun replaceDates(accountHash: String, dates: List<LocalDate>, rows: List<CourseOccurrenceEntity>) {
        dates.forEach { date ->
            deleteByDate(accountHash, date)
        }
        if (rows.isNotEmpty()) insertAll(rows)
    }

    @Query("DELETE FROM course_occurrence WHERE accountHash = :accountHash AND date = :date")
    suspend fun deleteByDate(accountHash: String, date: LocalDate)
}

@Dao
interface SyncMetaDao {

    @Query("SELECT * FROM sync_meta WHERE accountHash = :accountHash AND system = :system")
    fun observe(accountHash: String, system: String): Flow<SyncMetaEntity?>

    @Query("SELECT * FROM sync_meta WHERE accountHash = :accountHash AND system = :system")
    suspend fun get(accountHash: String, system: String): SyncMetaEntity?

    @Upsert
    suspend fun upsert(meta: SyncMetaEntity)

    @Query("DELETE FROM sync_meta WHERE accountHash = :accountHash")
    suspend fun deleteForAccount(accountHash: String)
}

@Dao
interface AttendanceDao {

    @Query(
        """
        SELECT * FROM attendance_record
        WHERE accountHash = :accountHash AND date = :date
        """
    )
    fun observeDay(accountHash: String, date: LocalDate): Flow<AttendanceRecordEntity?>

    @Query(
        """
        SELECT * FROM attendance_record
        WHERE accountHash = :accountHash AND month = :month
        ORDER BY date ASC
        """
    )
    fun observeMonth(accountHash: String, month: String): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT DISTINCT month FROM attendance_record WHERE accountHash = :accountHash ORDER BY month DESC")
    fun observeMonths(accountHash: String): Flow<List<String>>

    @Query(
        """
        SELECT * FROM attendance_record
        WHERE accountHash = :accountHash AND date BETWEEN :from AND :to
        ORDER BY date ASC
        """
    )
    suspend fun rangeOnce(accountHash: String, from: LocalDate, to: LocalDate): List<AttendanceRecordEntity>

    @Query("SELECT COUNT(*) FROM attendance_record WHERE accountHash = :accountHash AND month = :month")
    suspend fun countMonth(accountHash: String, month: String): Int

    @Query("DELETE FROM attendance_record WHERE accountHash = :accountHash AND month = :month")
    suspend fun deleteMonth(accountHash: String, month: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<AttendanceRecordEntity>)

    /** Atomic per-month replacement: a failed fetch must never wipe a good cached month. */
    @Transaction
    suspend fun replaceMonth(accountHash: String, month: String, rows: List<AttendanceRecordEntity>) {
        deleteMonth(accountHash, month)
        if (rows.isNotEmpty()) insertAll(rows)
    }
}

@Dao
interface AttendanceMetaDao {

    @Query("SELECT * FROM attendance_meta WHERE accountHash = :accountHash AND month = :month")
    fun observe(accountHash: String, month: String): Flow<AttendanceMetaEntity?>

    @Query("SELECT * FROM attendance_meta WHERE accountHash = :accountHash ORDER BY month DESC")
    suspend fun all(accountHash: String): List<AttendanceMetaEntity>

    @Upsert
    suspend fun upsert(meta: AttendanceMetaEntity)

    @Query("DELETE FROM attendance_meta WHERE accountHash = :accountHash")
    suspend fun deleteForAccount(accountHash: String)
}

@Dao
interface AttendancePunchDao {

    @Query(
        """
        SELECT * FROM attendance_punch
        WHERE accountHash = :accountHash AND date = :date
        ORDER BY time ASC
        """
    )
    fun observeDay(accountHash: String, date: LocalDate): Flow<List<AttendancePunchEntity>>

    @Query(
        """
        SELECT * FROM attendance_punch
        WHERE accountHash = :accountHash AND date BETWEEN :from AND :to
        ORDER BY time ASC
        """
    )
    fun observeRange(accountHash: String, from: LocalDate, to: LocalDate): Flow<List<AttendancePunchEntity>>

    @Query(
        """
        SELECT * FROM attendance_punch
        WHERE accountHash = :accountHash AND date BETWEEN :from AND :to
        ORDER BY time ASC
        """
    )
    suspend fun rangeOnce(accountHash: String, from: LocalDate, to: LocalDate): List<AttendancePunchEntity>

    @Query("SELECT COUNT(*) FROM attendance_punch WHERE accountHash = :accountHash AND date BETWEEN :from AND :to")
    suspend fun countRange(accountHash: String, from: LocalDate, to: LocalDate): Int

    @Query("DELETE FROM attendance_punch WHERE accountHash = :accountHash AND date BETWEEN :from AND :to")
    suspend fun deleteRange(accountHash: String, from: LocalDate, to: LocalDate)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<AttendancePunchEntity>)

    /** 只在完整解析通过后替换该区间的流水。 */
    @Transaction
    suspend fun replaceRange(
        accountHash: String,
        from: LocalDate,
        to: LocalDate,
        rows: List<AttendancePunchEntity>
    ) {
        deleteRange(accountHash, from, to)
        if (rows.isNotEmpty()) insertAll(rows)
    }
}
