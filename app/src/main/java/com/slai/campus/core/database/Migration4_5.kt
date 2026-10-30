package com.slai.campus.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Preserve all old attendance snapshots; their school period becomes known after a valid read. */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE attendance_meta ADD COLUMN periodWeeks TEXT")
        db.execSQL("ALTER TABLE attendance_meta ADD COLUMN periodUpdatedAt INTEGER")
    }
}
