package com.anve.app.data.location

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The device-local location queue. It lives in the `database` backup
 * domain, which every backup and device-transfer rule already excludes:
 * queued coordinates are sensitive and must not leave the device through
 * cloud backup or phone-to-phone transfer.
 *
 * Version 1 ships with no destructive fallback. A future schema change
 * must provide a real migration: silently dropping the queue would lose
 * captured, not-yet-shared location data.
 */
@Database(
    entities = [QueuedLocation::class],
    version = 2,
    exportSchema = true,
)
abstract class LocationDatabase : RoomDatabase() {
    abstract fun locationQueueDao(): LocationQueueDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_queue ADD COLUMN captureGeneration INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
