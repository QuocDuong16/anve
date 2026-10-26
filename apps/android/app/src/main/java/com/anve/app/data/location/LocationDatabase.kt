package com.anve.app.data.location

import androidx.room.Database
import androidx.room.RoomDatabase

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
    version = 1,
    exportSchema = true,
)
abstract class LocationDatabase : RoomDatabase() {
    abstract fun locationQueueDao(): LocationQueueDao
}
