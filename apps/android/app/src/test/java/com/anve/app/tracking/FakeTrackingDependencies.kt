package com.anve.app.tracking

import com.anve.app.data.api.ApiException
import com.anve.app.data.api.DeviceRegistrationDto
import com.anve.app.data.api.UserDto
import com.anve.app.data.auth.DeviceRegistrationProvider
import com.anve.app.data.location.LocationSyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.IOException

class FakeTrackingStore(
    initial: LocalTrackingState = LocalTrackingState(),
) : TrackingStore {
    private val _state = MutableStateFlow(initial)
    override val state: Flow<LocalTrackingState> = _state

    /** Ordered write log, used to prove revocation-before-upload ordering. */
    val writes = mutableListOf<String>()

    override suspend fun current(): LocalTrackingState = _state.value

    override suspend fun setTrackingRequested(requested: Boolean) {
        writes += "trackingRequested=$requested"
        _state.value = _state.value.copy(trackingRequested = requested)
    }

    override suspend fun setPendingConsentRevocation(pending: Boolean) {
        writes += "pendingConsentRevocation=$pending"
        _state.value = _state.value.copy(pendingConsentRevocation = pending)
    }

    override suspend fun setLocalConsentConfirmed(confirmed: Boolean) {
        writes += "localConsentConfirmed=$confirmed"
        _state.value = _state.value.copy(localConsentConfirmed = confirmed)
    }

    override suspend fun setConsentOwner(ownerUserId: String?) {
        writes += "consentOwner=$ownerUserId"
        _state.value = _state.value.copy(consentOwnerUserId = ownerUserId)
    }

    override suspend fun setLastSuccessfulUpload(atMillis: Long) {
        _state.value = _state.value.copy(
            lastSuccessfulUploadAtMillis = atMillis,
            lastSyncErrorCode = null,
        )
    }

    override suspend fun setLastSyncError(code: String?) {
        _state.value = _state.value.copy(lastSyncErrorCode = code)
    }
}

class FakePermissions(
    var value: PermissionSnapshot = PermissionSnapshot(
        location = LocationPermissionState.GRANTED_PRECISE,
        backgroundLocation = true,
        notifications = true,
        locationServicesEnabled = true,
    ),
) : TrackingPermissions {
    override fun snapshot(): PermissionSnapshot = value
}

class FakeLocationProvider : LocationProvider {
    var available = true
    var lastOptions: LocationRequestOptions? = null
    val optionsHistory = mutableListOf<LocationRequestOptions>()
    private val fixes = MutableSharedFlow<LocationFix>(extraBufferCapacity = 64)
    var throwOnNextFix: Throwable? = null
    var activeCollectors = 0

    override fun isAvailable(): Boolean = available

    override fun updates(options: LocationRequestOptions): Flow<LocationFix> = flow {
        lastOptions = options
        optionsHistory += options
        activeCollectors++
        try {
            fixes.collect { fix ->
                throwOnNextFix?.let { error ->
                    throwOnNextFix = null
                    throw error
                }
                emit(fix)
            }
        } finally {
            activeCollectors--
        }
    }

    suspend fun emit(fix: LocationFix) {
        fixes.emit(fix)
    }
}

class FakeConnectivity(var online: Boolean = true) : ConnectivityMonitor {
    private val state = MutableStateFlow(online)

    override fun isOnline(): Boolean = state.value

    override fun observe(): Flow<Boolean> = state.map { it }

    fun updateOnline(value: Boolean) {
        online = value
        state.value = value
    }
}

class FakeServiceController(var startResult: Boolean = true) : TrackingServiceController {
    var starts = 0
    var stops = 0

    override fun start(): Boolean {
        starts++
        return startResult
    }

    override fun stop() {
        stops++
    }
}

class FakeSyncScheduler : LocationSyncScheduler {
    var scheduled = 0
    var immediate = 0

    override fun ensureScheduled() {
        scheduled++
    }

    override fun enqueueImmediate() {
        immediate++
    }
}

class FakeDeviceIdentity : DeviceRegistrationProvider {
    override suspend fun registration(): DeviceRegistrationDto =
        DeviceRegistrationDto(clientDeviceId = "device-owner-0001")
}

/**
 * Server-side consent/circle state. `pendingAtConsentCall` lets a test
 * prove the durable revocation flag was written before the request.
 */
class FakeServerGateway(
    var consent: Boolean = true,
    var consentVersion: Long = 0,
    var hasCircle: Boolean = true,
    private val store: FakeTrackingStore? = null,
) : TrackingServerGateway {

    var failConsentWith: Exception? = null
    var failCurrentUserWith: Exception? = null
    var pendingAtConsentCall: Boolean? = null
    var beforeCurrentUser: (suspend () -> Unit)? = null
    val consentCalls = mutableListOf<Boolean>()

    override suspend fun currentUser(): UserDto {
        beforeCurrentUser?.also {
            beforeCurrentUser = null
            it()
        }
        failCurrentUserWith?.let { throw it }
        return user(consent)
    }

    override suspend fun setLocationSharing(
        enabled: Boolean,
        expectedVersion: Long,
        ownerUserId: String,
        operationId: String,
    ): UserDto {
        if (ownerUserId != "u1") throw java.io.IOException("owner mismatch")
        if (expectedVersion != consentVersion) {
            throw ApiException("CONSENT_VERSION_CONFLICT", "version conflict", null)
        }
        consentCalls += enabled
        pendingAtConsentCall = store?.current()?.pendingConsentRevocation
        failConsentWith?.let { throw it }
        consent = enabled
        consentVersion++
        return user(consent)
    }

    override suspend fun hasCircle(): Boolean = hasCircle

    private fun user(sharing: Boolean) = UserDto(
        id = "u1",
        email = "owner@example.com",
        displayName = "Owner",
        avatarUrl = null,
        locationSharingEnabled = sharing,
        createdAt = "2026-10-26T00:00:00Z",
        locationSharingVersion = consentVersion,
    )
}

class FakeSyncGateway(
    var owner: String = "u1",
) : com.anve.app.data.location.LocationSyncGateway {

    val uploads = mutableListOf<List<com.anve.app.data.api.LocationEventDto>>()
    var uploadHandler: suspend (List<com.anve.app.data.api.LocationEventDto>) ->
        com.anve.app.data.api.LocationBatchResponseDto = { events ->
        com.anve.app.data.api.LocationBatchResponseDto(accepted = events.map { it.eventId })
    }

    override suspend fun currentUserId(): String = owner

    override suspend fun upload(
        events: List<com.anve.app.data.api.LocationEventDto>,
    ): com.anve.app.data.api.LocationBatchResponseDto {
        uploads += events
        return uploadHandler(events)
    }

}

fun testFix(
    eventId: String = "event-1",
    recordedAtMillis: Long = 1_000_000L,
    elapsedRealtimeNanos: Long = recordedAtMillis * 1_000_000,
    latitude: Double = 10.7769,
    longitude: Double = 106.7009,
    accuracy: Double = 10.0,
    speed: Double? = null,
): LocationFix = LocationFix(
    eventId = eventId,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracy,
    altitudeMeters = null,
    speedMetersPerSecond = speed,
    bearingDegrees = null,
    provider = "fused",
    isMock = false,
    recordedAtMillis = recordedAtMillis,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
)

fun networkError(): IOException = IOException("network down")
