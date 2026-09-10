package com.slai.campus.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        CourseOccurrenceEntity::class,
        SyncMetaEntity::class,
        AttendanceRecordEntity::class,
        AttendanceMetaEntity::class,
        AttendancePunchEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
    abstract fun syncMetaDao(): SyncMetaDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun attendanceMetaDao(): AttendanceMetaDao
    abstract fun attendancePunchDao(): AttendancePunchDao

    companion object {
        const val NAME = "slai-campus.db"
    }
}
