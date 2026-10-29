package com.anve.app.tracking

/**
 * One provider callback, normalized before it reaches the policy and the
 * queue. [eventId] is generated at capture time and becomes the server
 * idempotency key; [elapsedRealtimeNanos] orders fixes independently of
 * wall-clock changes.
 */
data class LocationFix(
    val eventId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Double?,
    val bearingDegrees: Double?,
    val provider: String,
    val isMock: Boolean,
    val recordedAtMillis: Long,
    val elapsedRealtimeNanos: Long,
)

/**
 * The movement dimension of the engine. JOURNEY is a policy hook for M7:
 * it is never inferred automatically in M3.
 */
enum class MotionState {
    IDLE,
    STATIONARY,
    MOVING,
    JOURNEY,
}

/**
 * The operational dimension. These are deliberately separate from
 * [MotionState]: "offline" says nothing about whether the person is walking,
 * and forcing both into one enum produces nonsensical combinations.
 */
enum class OperationalState {
    /** Service and provider are actually delivering fixes. */
    ACTIVE,

    /** Running, but a limitation applies (approximate, power saver, no fix yet). */
    DEGRADED,

    /** Acquisition continues, uploads wait for connectivity. */
    OFFLINE,

    /** Not running, or waiting for the user to resume after process death. */
    PAUSED,
}

enum class LocationPermissionState {
    NOT_REQUESTED,
    GRANTED_PRECISE,
    GRANTED_APPROXIMATE,
    DENIED,
}

/** Stable, user-actionable failure reasons. Never contains coordinates. */
enum class TrackingProblem {
    PERMISSION_DENIED,
    PERMISSION_REVOKED,
    LOCATION_DISABLED,
    PROVIDER_UNAVAILABLE,
    CONSENT_REQUIRED,
    CONSENT_CONFLICT,
    CONSENT_STORAGE_UNAVAILABLE,
    CONSENT_QUEUE_PURGE_FAILED,
    CIRCLE_REQUIRED,
    SESSION_UNAVAILABLE,
    CHECKPOINT_UNAVAILABLE,
    SERVICE_START_FAILED,
    SERVICE_STOP_FAILED,
    PROVIDER_ERROR,
    QUEUE_UNAVAILABLE,
    RECOVERY_REQUIRED,

    /** The device clock is far enough ahead that the server would reject
     * every fix as a future capture. The timestamp is never rewritten. */
    CLOCK_SKEW,
}

/**
 * Everything the tracking UI may show. `consentEnabled` is the effective
 * local consent state after owner and privacy-gate checks; `serverConsentEnabled`
 * is the separate account snapshot. Neither field proves live collection,
 * which is reported by `running` and `operational`.
 */
data class TrackingStatus(
    /** Whether the storage layer currently holds a session for this account. */
    val sessionReady: Boolean = false,
    /** Effective local consent; false while this owner's emergency privacy gate is closed. */
    val consentEnabled: Boolean = false,
    val consentConfirmedByServer: Boolean = false,
    /** Last authoritative account consent snapshot; independent of local collection admission. */
    val serverConsentEnabled: Boolean? = null,
    /** True only when the current owner has passed the in-process privacy gate. */
    val locationAdmissionOpen: Boolean = false,
    val consentSyncPending: Boolean = false,
    val consentConflict: Boolean = false,
    /** Authoritative account state observed during a conflict; local tracking stays closed. */
    val conflictServerConsent: Boolean? = null,
    val trackingRequested: Boolean = false,
    val serviceRunning: Boolean = false,
    val providerActive: Boolean = false,
    val running: Boolean = false,
    val motion: MotionState = MotionState.IDLE,
    val operational: OperationalState = OperationalState.PAUSED,
    val permission: LocationPermissionState = LocationPermissionState.NOT_REQUESTED,
    val backgroundPermissionGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val locationServicesEnabled: Boolean = true,
    val batterySaver: Boolean = false,
    val online: Boolean = true,
    val lastFixAtMillis: Long? = null,
    val lastAccuracyMeters: Double? = null,
    val lastUploadAtMillis: Long? = null,
    val pendingQueueCount: Int = 0,
    val privacyDiscardedPoints: Boolean = false,
    val lastError: TrackingProblem? = null,
    val lastErrorAtMillis: Long? = null,
    val recoveryRequired: Boolean = false,
) {
    /** True only when the OS is actually delivering fixes, not on request. */
    val activelyTracking: Boolean
        get() = running && serviceRunning && providerActive
}

/** Provider request parameters derived by the adaptive policy. */
data class LocationRequestOptions(
    val intervalMillis: Long,
    val minDistanceMeters: Float,
    val highAccuracy: Boolean,
)

/** Why a provider fix was not persisted. */
enum class FixRejection {
    STALE,
    OUT_OF_ORDER,
    INACCURATE,
    DUPLICATE,
}
