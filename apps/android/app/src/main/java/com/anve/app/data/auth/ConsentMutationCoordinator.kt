package com.anve.app.data.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Coordinates short local consent mutations; network calls never hold this lock. */
class ConsentMutationCoordinator {
    private enum class AdmissionMode { UNVERIFIED, BLOCKED, OPEN }

    private data class AdmissionState(
        val mode: AdmissionMode,
        val generation: Long,
    )

    private val mutex = Mutex()
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val admission = ConcurrentHashMap<String, AdmissionState>()
    private val ownerlessBarrier = AtomicLong(0)

    /** Closes one owner's admission synchronously at the local OFF barrier. */
    fun closeLocationAdmission(ownerUserId: String?): Long {
        if (ownerUserId != null) return advanceGeneration(ownerUserId)

        // If the owner cannot be resolved, close all accounts already known
        // to this process. A later account must still pass its own consent
        // reconciliation; it never inherits an earlier account's OPEN gate.
        val barrier = ownerlessBarrier.incrementAndGet()
        generations.forEach { (owner, counter) ->
            val next = counter.updateAndGet { maxOf(it + 1L, barrier) }
            admission[owner] = AdmissionState(AdmissionMode.BLOCKED, next)
        }
        return barrier
    }

    /** Opens only after the exact, current explicit consent operation is confirmed. */
    fun openLocationAdmission(ownerUserId: String, generation: Long): Boolean {
        if (currentGeneration(ownerUserId) != generation) return false
        var opened = false
        admission.compute(ownerUserId) { _, current ->
            if (currentGeneration(ownerUserId) == generation) {
                opened = true
                AdmissionState(AdmissionMode.OPEN, generation)
            } else {
                current
            }
        }
        return opened
    }

    /**
     * A server snapshot may establish an unverified owner's gate. It cannot
     * clear a local OFF/conflict barrier; that needs a fresh confirmed user
     * mutation with its own generation.
     */
    fun reconcileLocationAdmission(ownerUserId: String, enabled: Boolean): Boolean {
        if (!enabled) {
            closeLocationAdmission(ownerUserId)
            return false
        }

        var opened = false
        admission.compute(ownerUserId) { _, current ->
            val generation = currentGeneration(ownerUserId)
            when {
                current?.mode == AdmissionMode.OPEN && current.generation == generation -> {
                    opened = true
                    current
                }
                current == null || current.mode == AdmissionMode.UNVERIFIED -> {
                    opened = true
                    AdmissionState(AdmissionMode.OPEN, generation)
                }
                else -> current
            }
        }
        return opened
    }

    /** Restores the fail-closed gate for a durable operation after process restart. */
    fun restoreBlockedAdmission(ownerUserId: String, generation: Long) {
        val counter = generations.computeIfAbsent(ownerUserId) { AtomicLong(0) }
        val current = counter.updateAndGet { maxOf(it, generation, ownerlessBarrier.get()) }
        admission[ownerUserId] = AdmissionState(AdmissionMode.BLOCKED, current)
    }

    fun isLocationAdmissionClosed(ownerUserId: String?): Boolean {
        if (ownerUserId.isNullOrBlank()) return true
        val current = admission[ownerUserId] ?: return true
        return current.mode != AdmissionMode.OPEN || current.generation != currentGeneration(ownerUserId)
    }

    suspend fun <T> withLocalMutation(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Linearizes an upload start against durable consent mutations. The
     * actual HTTP request starts after this short admission lock is released.
     */
    suspend fun authorizeLocationUpload(
        ownerUserId: String,
        hasPendingConsentMutation: suspend () -> Boolean,
    ): Boolean = mutex.withLock {
        !isLocationAdmissionClosed(ownerUserId) && !hasPendingConsentMutation()
    }

    /** Grants a consent-request start only while that durable generation is still current. */
    suspend fun authorizeConsentRequest(isCurrentGeneration: suspend () -> Boolean): Boolean =
        mutex.withLock { isCurrentGeneration() }

    suspend fun <T> withNextGeneration(
        ownerUserId: String,
        minimumGeneration: Long = 0,
        block: suspend (Long) -> T,
    ): T = mutex.withLock {
        val next = advanceGeneration(ownerUserId, minimumGeneration)
        block(next)
    }

    suspend fun nextGeneration(ownerUserId: String): Long = mutex.withLock {
        advanceGeneration(ownerUserId)
    }

    suspend fun isCurrent(ownerUserId: String, generation: Long): Boolean =
        currentGeneration(ownerUserId) == generation

    suspend fun <T> ifCurrent(
        ownerUserId: String,
        generation: Long,
        block: suspend () -> T,
    ): T? = mutex.withLock {
        if (currentGeneration(ownerUserId) == generation) block() else null
    }

    private fun advanceGeneration(ownerUserId: String, minimumGeneration: Long = 0): Long {
        val counter = generations.computeIfAbsent(ownerUserId) { AtomicLong(0) }
        val next = counter.updateAndGet { maxOf(it, minimumGeneration, ownerlessBarrier.get()) + 1L }
        admission[ownerUserId] = AdmissionState(AdmissionMode.BLOCKED, next)
        return next
    }

    private fun currentGeneration(ownerUserId: String): Long =
        generations[ownerUserId]?.get() ?: 0L
}
