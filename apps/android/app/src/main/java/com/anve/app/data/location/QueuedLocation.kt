package com.anve.app.data.location

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.anve.app.tracking.LocationFix

/**
 * One accepted location fix waiting for upload. The row is written before
 * the fix is considered captured, so losing the process or the network can
 * never lose an accepted point.
 *
 * [eventId] is the server idempotency key and the primary key: re-enqueuing
 * the same fix cannot duplicate it, and a retry after a lost response is
 * recognised by the server as a duplicate.
 *
 * [ownerUserId] scopes every row to the account that produced it. Sync
 * refuses to upload rows of another account, so an account switch (or a
 * remotely revoked session followed by a new sign-in) cannot leak a
 * previous account's location.
 *
 * No coordinates ever appear in logs or diagnostics; this table is the only
 * place they are stored on the device.
 */
@Entity(
    tableName = "location_queue",
    indices = [
        Index(value = ["ownerUserId", "recordedAtMillis"]),
        Index(value = ["recordedAtMillis"]),
    ],
)
data class QueuedLocation(
    @PrimaryKey val eventId: String,
    val ownerUserId: String,
    val clientDeviceId: String,
    val recordedAtMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Double?,
    val bearingDegrees: Double?,
    val provider: String,
    val isMock: Boolean,
    val schemaVersion: Int,
    val createdAtMillis: Long,
    val attemptCount: Int = 0,
    val lastAttemptAtMillis: Long? = null,
)

/** Converts a provider fix into a durable queue row. */
fun LocationFix.toQueuedLocation(
    ownerUserId: String,
    clientDeviceId: String,
    createdAtMillis: Long,
): QueuedLocation = QueuedLocation(
    eventId = eventId,
    ownerUserId = ownerUserId,
    clientDeviceId = clientDeviceId,
    recordedAtMillis = recordedAtMillis,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
    speedMetersPerSecond = speedMetersPerSecond,
    bearingDegrees = bearingDegrees,
    provider = provider,
    isMock = isMock,
    schemaVersion = LOCATION_SCHEMA_VERSION,
    createdAtMillis = createdAtMillis,
)

const val LOCATION_SCHEMA_VERSION = 1
