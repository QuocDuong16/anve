package com.anve.app.data.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory Room DAO with the same ordering and filtering semantics the
 * queries express. SQLite itself is a platform concern; these tests pin the
 * queue rules (owner scope, bound, retention) that run above it.
 */
class FakeLocationQueueDao : LocationQueueDao {

    val rows = linkedMapOf<String, QueuedLocation>()
    var forcedCount: Int? = null
    var lastRetentionCutoff: Long? = null
    var lastDeleteOldest: Pair<String, Int>? = null
    var lastAttemptMarked: Pair<List<String>, Long>? = null
    var beforeMarkAttempt: (suspend () -> Unit)? = null
    var failDeleteAll = false

    private val counts = mutableMapOf<String, MutableStateFlow<Int>>()

    override suspend fun insert(row: QueuedLocation): Long {
        if (rows.containsKey(row.eventId)) return -1
        rows[row.eventId] = row
        publishCounts()
        return 1
    }

    override suspend fun pending(ownerUserId: String, limit: Int): List<QueuedLocation> =
        rows.values
            .filter { it.ownerUserId == ownerUserId }
            .sortedBy { it.recordedAtMillis }
            .take(limit)

    override suspend fun deleteByEventIds(eventIds: List<String>): Int {
        val before = rows.size
        eventIds.forEach { rows.remove(it) }
        publishCounts()
        return before - rows.size
    }

    override suspend fun deleteAll(): Int {
        if (failDeleteAll) throw java.io.IOException("simulated queue purge failure")
        val count = rows.size
        rows.clear()
        publishCounts()
        return count
    }

    override suspend fun deleteOtherAccounts(ownerUserId: String): Int {
        val before = rows.size
        rows.entries.removeAll { it.value.ownerUserId != ownerUserId }
        publishCounts()
        return before - rows.size
    }

    override suspend fun deleteOlderThan(cutoffMillis: Long): Int {
        lastRetentionCutoff = cutoffMillis
        val before = rows.size
        rows.entries.removeAll { it.value.recordedAtMillis < cutoffMillis }
        publishCounts()
        return before - rows.size
    }

    override suspend fun count(ownerUserId: String): Int =
        forcedCount ?: rows.values.count { it.ownerUserId == ownerUserId }

    override fun observeCount(ownerUserId: String): Flow<Int> =
        counts.getOrPut(ownerUserId) { MutableStateFlow(countNow(ownerUserId)) }

    override suspend fun countAll(): Int = rows.size

    override suspend fun deleteOldest(ownerUserId: String, count: Int): Int {
        lastDeleteOldest = ownerUserId to count
        val oldest = rows.values
            .filter { it.ownerUserId == ownerUserId }
            .sortedBy { it.recordedAtMillis }
            .take(count)
        oldest.forEach { rows.remove(it.eventId) }
        publishCounts()
        return oldest.size
    }

    override suspend fun markAttempt(eventIds: List<String>, nowMillis: Long): Int {
        beforeMarkAttempt?.also {
            beforeMarkAttempt = null
            it()
        }
        lastAttemptMarked = eventIds to nowMillis
        var updated = 0
        eventIds.forEach { id ->
            rows[id]?.let { row ->
                rows[id] = row.copy(
                    attemptCount = row.attemptCount + 1,
                    lastAttemptAtMillis = nowMillis,
                )
                updated++
            }
        }
        return updated
    }

    private fun publishCounts() {
        val owners = rows.values.map { it.ownerUserId }.toSet() + counts.keys
        owners.forEach { owner ->
            counts.getOrPut(owner) { MutableStateFlow(0) }.value = countNow(owner)
        }
    }

    private fun countNow(ownerUserId: String): Int =
        forcedCount ?: rows.values.count { it.ownerUserId == ownerUserId }
}
