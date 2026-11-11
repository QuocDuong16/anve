package com.anve.app.data.location

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Aggregate only; diagnostics must never read or expose coordinate columns. */
data class QueueDiagnostics(
    val queuedPoints: Int,
    val oldestCreatedAtMillis: Long?,
    val attempts: Long,
    val retriedPoints: Int,
)

/** Room access for the durable offline location queue. */
@Dao
interface LocationQueueDao {

    @Query(
        "SELECT COUNT(*) AS queuedPoints, MIN(createdAtMillis) AS oldestCreatedAtMillis, " +
            "COALESCE(SUM(attemptCount), 0) AS attempts, " +
            "COALESCE(SUM(CASE WHEN attemptCount > 1 THEN 1 ELSE 0 END), 0) AS retriedPoints " +
            "FROM location_queue WHERE ownerUserId = :ownerUserId",
    )
    suspend fun diagnostics(ownerUserId: String): QueueDiagnostics

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: QueuedLocation): Long

    /** Oldest points first: event-time order, independent of upload order. */
    @Query(
        "SELECT * FROM location_queue WHERE ownerUserId = :ownerUserId " +
            "ORDER BY recordedAtMillis ASC LIMIT :limit",
    )
    suspend fun pending(ownerUserId: String, limit: Int): List<QueuedLocation>

    @Query("DELETE FROM location_queue WHERE eventId IN (:eventIds)")
    suspend fun deleteByEventIds(eventIds: List<String>): Int

    @Query("DELETE FROM location_queue")
    suspend fun deleteAll(): Int

    @Query("DELETE FROM location_queue WHERE ownerUserId != :ownerUserId")
    suspend fun deleteOtherAccounts(ownerUserId: String): Int

    @Query("DELETE FROM location_queue WHERE recordedAtMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query("SELECT COUNT(*) FROM location_queue WHERE ownerUserId = :ownerUserId")
    suspend fun count(ownerUserId: String): Int

    @Query("SELECT COUNT(*) FROM location_queue WHERE ownerUserId = :ownerUserId")
    fun observeCount(ownerUserId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM location_queue")
    suspend fun countAll(): Int

    @Query(
        "DELETE FROM location_queue WHERE eventId IN (" +
            "SELECT eventId FROM location_queue WHERE ownerUserId = :ownerUserId " +
            "ORDER BY recordedAtMillis ASC LIMIT :count)",
    )
    suspend fun deleteOldest(ownerUserId: String, count: Int): Int

    @Query(
        "UPDATE location_queue SET attemptCount = attemptCount + 1, lastAttemptAtMillis = :nowMillis " +
            "WHERE eventId IN (:eventIds)",
    )
    suspend fun markAttempt(eventIds: List<String>, nowMillis: Long): Int
}
