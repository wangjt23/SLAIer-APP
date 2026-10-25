package com.slai.campus.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** A teaching week may appear in adjacent month snapshots; preserve both without losing v3 rows. */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE attendance_record_new (
                accountHash TEXT NOT NULL,
                date INTEGER NOT NULL,
                month TEXT NOT NULL,
                weekDay TEXT,
                dayType TEXT,
                isHoliday TEXT,
                firstSwipe INTEGER,
                lastSwipe INTEGER,
                enterCount INTEGER,
                exitCount INTEGER,
                durationText TEXT,
                durationMinutes INTEGER,
                qualified INTEGER,
                leave INTEGER,
                appeal INTEGER,
                swipes TEXT,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(accountHash, month, date)
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO attendance_record_new (
                accountHash, date, month, weekDay, dayType, isHoliday, firstSwipe, lastSwipe,
                enterCount, exitCount, durationText, durationMinutes, qualified, leave, appeal,
                swipes, updatedAt
            ) SELECT
                accountHash, date, month, weekDay, dayType, isHoliday, firstSwipe, lastSwipe,
                enterCount, exitCount, durationText, durationMinutes, qualified, leave, appeal,
                swipes, updatedAt
            FROM attendance_record
        """.trimIndent())
        db.execSQL("DROP TABLE attendance_record")
        db.execSQL("ALTER TABLE attendance_record_new RENAME TO attendance_record")
        db.execSQL("CREATE INDEX index_attendance_record_accountHash_month ON attendance_record (accountHash, month)")
    }
}
