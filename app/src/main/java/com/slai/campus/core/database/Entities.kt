package com.slai.campus.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * One cached teaching session.
 *
 * `accountHash` is part of every query: the plan requires that switching accounts cannot surface the
 * previous account's timetable, so the cache is partitioned rather than cleared.
 */
@Entity(
    tableName = "course_occurrence",
    indices = [
        Index(value = ["accountHash", "date"]),
        Index(value = ["accountHash", "updatedAt"])
    ]
)
data class CourseOccurrenceEntity(
    @PrimaryKey val id: String,
    val accountHash: String,
    val courseName: String,
    val teacher: String?,
    val location: String?,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    /** [com.slai.campus.domain.schedule.ScheduleSource] name. */
    val source: String,
    val weekIndex: Int?,
    val periodStart: Int?,
    val periodEnd: Int?,
    val courseCode: String?,
    val teachingClass: String?,
    val campus: String?,
    /** Wall-clock millis of the sync that produced this row. */
    val updatedAt: Long
)

/**
 * Per-system sync bookkeeping. Survives a failed refresh so the UI can say *when* the data was last
 * good and *why* the latest attempt failed.
 */
@Entity(tableName = "sync_meta", primaryKeys = ["accountHash", "system"])
data class SyncMetaEntity(
    val accountHash: String,
    /** [com.slai.campus.core.common.SchoolSystem] key. */
    val system: String,
    val lastSuccessAt: Long?,
    val lastAttemptAt: Long?,
    val parserVersion: String?,
    val lastError: String?,
    /** [com.slai.campus.core.common.IntegrationMode] name actually used for the last success. */
    val integrationMode: String?,
    /** Anchor discovered for the semester, as an ISO date. */
    @ColumnInfo(name = "semester_first_week_monday") val semesterFirstWeekMonday: String?
)

/**
 * One cached attendance day.
 *
 * Replaces the old, vague `checkin_status`: the school's ledger has a real record per day, so the app
 * stores exactly that and can answer "when did I swipe in and out on 9 月 9 日" offline.
 */
@Entity(
    tableName = "attendance_record",
    primaryKeys = ["accountHash", "date"],
    indices = [Index(value = ["accountHash", "month"])]
)
data class AttendanceRecordEntity(
    val accountHash: String,
    val date: LocalDate,
    /** `YYYY-MM`, used to load one month at a time. */
    val month: String,
    val weekDay: String?,
    val dayType: String?,
    val isHoliday: String?,
    val firstSwipe: LocalTime?,
    val lastSwipe: LocalTime?,
    val enterCount: Int?,
    val exitCount: Int?,
    val durationText: String?,
    val durationMinutes: Int?,
    val qualified: Boolean?,
    val leave: Boolean?,
    val appeal: Boolean?,
    val swipes: String?,
    val updatedAt: Long
)

/** Per-month attendance sync metadata, so a cached month can be shown without a request. */
@Entity(tableName = "attendance_meta", primaryKeys = ["accountHash", "month"])
data class AttendanceMetaEntity(
    val accountHash: String,
    val month: String,
    val lastSuccessAt: Long?,
    val lastAttemptAt: Long?,
    val lastError: String?,
    /** Server-provided summary counters, serialised as `key=value;` pairs. */
    val stats: String?
)

/**
 * 一条闸机刷卡记录。
 *
 * 这是"今天打卡多久"的唯一数据来源：考勤汇总接口只给每天的累计时长，且当天的值恒为 0（未结算），
 * 只有刷卡流水带具体时间与进出方向。
 */
@Entity(
    tableName = "attendance_punch",
    primaryKeys = ["id"],
    indices = [Index(value = ["accountHash", "date"]), Index(value = ["accountHash", "time"])]
)
data class AttendancePunchEntity(
    val id: String,
    val accountHash: String,
    val time: LocalDateTime,
    val date: LocalDate,
    /** IN / OUT / UNKNOWN */
    val direction: String,
    val channel: String?,
    val place: String?,
    val openingType: String?,
    val result: String?,
    val updatedAt: Long
)
