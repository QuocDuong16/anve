package com.anve.app.data.location

import android.os.SystemClock

/** A checkpoint is usable only for fixes taken after its receipt in this process. */
class CaptureGenerationStore(private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos) {
    private data class Checkpoint(val generation: Long, val receivedNanos: Long)
    private val checkpoints = mutableMapOf<String, Checkpoint>()

    @Synchronized
    fun current(owner: String, captureNanos: Long): Long {
        val checkpoint = checkpoints[owner] ?: return 0
        return if (captureNanos >= checkpoint.receivedNanos) checkpoint.generation else 0
    }

    @Synchronized
    fun advance(owner: String, generation: Long) {
        if (generation > (checkpoints[owner]?.generation ?: 0)) {
            checkpoints[owner] = Checkpoint(generation, nowNanos())
        }
    }
}
