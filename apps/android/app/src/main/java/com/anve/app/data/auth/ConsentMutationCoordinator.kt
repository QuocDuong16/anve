package com.anve.app.data.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Coordinates short local consent mutations; network calls never hold this lock. */
class ConsentMutationCoordinator {
    private val mutex = Mutex()
    private val generations = mutableMapOf<String, Long>()
    private val locationAdmissionClosed = AtomicBoolean(false)

    /** Closes location and upload admission synchronously at the local OFF barrier. */
    fun closeLocationAdmission() {
        locationAdmissionClosed.set(true)
    }

    /** Reopens admission only after an explicit ON operation is server-confirmed. */
    fun openLocationAdmission() {
        locationAdmissionClosed.set(false)
    }

    fun isLocationAdmissionClosed(): Boolean = locationAdmissionClosed.get()

    suspend fun <T> withLocalMutation(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Linearizes a new location upload against durable consent mutations.
     * The caller receives a start permit while holding the same short lock
     * used to persist consent intent, then performs network I/O after release.
     */
    suspend fun authorizeLocationUpload(hasPendingConsentMutation: suspend () -> Boolean): Boolean =
        mutex.withLock { !locationAdmissionClosed.get() && !hasPendingConsentMutation() }

    /** Grants a consent-request start only while that durable generation is still current. */
    suspend fun authorizeConsentRequest(isCurrentGeneration: suspend () -> Boolean): Boolean =
        mutex.withLock { isCurrentGeneration() }

    suspend fun <T> withNextGeneration(
        ownerUserId: String,
        minimumGeneration: Long = 0,
        block: suspend (Long) -> T,
    ): T = mutex.withLock {
        val next = maxOf((generations[ownerUserId] ?: 0L) + 1L, minimumGeneration + 1L)
        generations[ownerUserId] = next
        block(next)
    }

    suspend fun nextGeneration(ownerUserId: String): Long = mutex.withLock {
        val next = (generations[ownerUserId] ?: 0L) + 1L
        generations[ownerUserId] = next
        next
    }

    suspend fun isCurrent(ownerUserId: String, generation: Long): Boolean = mutex.withLock {
        generations[ownerUserId] == generation
    }

    suspend fun <T> ifCurrent(
        ownerUserId: String,
        generation: Long,
        block: suspend () -> T,
    ): T? = mutex.withLock {
        if (generations[ownerUserId] == generation) block() else null
    }
}
