package com.anve.app.tracking

import kotlinx.coroutines.flow.StateFlow

interface JourneyTrackingControl {
    val status: StateFlow<TrackingStatus>
    fun journeyAdmissionRevision(): Long
    fun enableJourneyPriority(owner: String, id: String, expiresAtMillis: Long, revision: Long): Boolean
    fun stopJourneyPriority(owner: String)
}
