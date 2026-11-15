package com.anve.app.data.location

import android.os.SystemClock

/** A checkpoint is usable only for fixes taken after its receipt in this process. */
class CaptureGenerationStore(private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos) {
    private data class Checkpoint(val generation: Long, val receivedNanos: Long)
    private val checkpoints = mutableMapOf<String, Checkpoint>()
    private var revision = 0L

    @Synchronized
    fun current(owner: String, captureNanos: Long): Long {
        val checkpoint = checkpoints[owner] ?: return 0
        return if (captureNanos > checkpoint.receivedNanos) checkpoint.generation else 0
    }

    @Synchronized
    fun has(owner: String): Boolean = (checkpoints[owner]?.generation ?: 0) > 0

    @Synchronized
    fun clear() { revision++; checkpoints.clear() }

    @Synchronized
    fun revision(): Long = revision

    /** Records [generation] if it matches [expectedRevision] and advances the owner's checkpoint. */
    @Synchronized
    fun advanceIfCurrent(owner: String, generation: Long, expectedRevision: Long): Boolean {
        if (revision != expectedRevision) return false
        return advance(owner, generation)
    }

    /** Records [generation] for [owner] when it advances past any stored checkpoint. */
    @Synchronized
    fun advance(owner: String, generation: Long): Boolean {
        val current = checkpoints[owner]?.generation ?: 0L
        if (generation <= current) return false
        checkpoints[owner] = Checkpoint(generation, nowNanos())
        return true
    }
}
