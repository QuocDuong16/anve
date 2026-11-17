package com.anve.app.tracking

import android.content.Context
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import androidx.core.location.LocationCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * The only way the engine obtains coordinates. Tests substitute a fake
 * provider; production uses the Play Services FusedLocationProvider.
 */
interface LocationProvider {
    /** Whether the platform provider can run on this device. */
    fun isAvailable(): Boolean

    /** Registers for updates; cancelling the collector unregisters it. */
    fun updates(options: LocationRequestOptions): Flow<LocationFix>

    /**
     * Registers for updates; [requestInitialFix] triggers an immediate fresh
     * fix only on session start or capture-checkpoint re-priming.
     */
    fun updates(options: LocationRequestOptions, requestInitialFix: Boolean): Flow<LocationFix> =
        updates(options)
}

/**
 * Fused provider implementation. It rejects pre-subscription cached fixes so
 * a fresh start or capture-checkpoint roll only receives live fixes recorded
 * after subscription.
 */
class FusedLocationProvider(context: Context) : LocationProvider {

    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    override fun isAvailable(): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS

    override fun updates(options: LocationRequestOptions): Flow<LocationFix> =
        updates(options, requestInitialFix = true)

    override fun updates(
        options: LocationRequestOptions,
        requestInitialFix: Boolean,
    ): Flow<LocationFix> = callbackFlow {
        val subscribedAtNanos = SystemClock.elapsedRealtimeNanos()
        val priority = if (options.highAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        fun deliverIfFresh(location: Location) {
            // Ignore cached fixes recorded before this subscription so starting
            // or re-priming does not immediately replay a pre-checkpoint point.
            if (location.elapsedRealtimeNanos <= subscribedAtNanos) return
            trySend(location.toFix())
        }

        // Play Services treats minUpdateDistanceMeters as an AND gate with
        // intervalMillis, starving stationary heartbeats; AdaptiveTrackingPolicy
        // handles displacement vs. heartbeat filtering instead.
        val maxUpdateAgeMillis = minOf(
            options.intervalMillis / 2,
            AdaptiveTrackingPolicy.MAX_FIX_AGE_MILLIS / 2,
        )
        val request = LocationRequest.Builder(priority, options.intervalMillis)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateAgeMillis(maxUpdateAgeMillis)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach(::deliverIfFresh)
            }
        }
        val currentLocationToken = if (requestInitialFix) CancellationTokenSource() else null

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { error ->
                    close(error)
                }
            if (currentLocationToken != null) {
                val currentRequest = CurrentLocationRequest.Builder()
                    .setPriority(priority)
                    .setMaxUpdateAgeMillis(0L)
                    .build()
                client.getCurrentLocation(currentRequest, currentLocationToken.token)
                    .addOnSuccessListener { location ->
                        if (location != null) deliverIfFresh(location)
                    }
            }
        } catch (e: SecurityException) {
            currentLocationToken?.cancel()
            close(e)
            return@callbackFlow
        }

        awaitClose {
            currentLocationToken?.cancel()
            client.removeLocationUpdates(callback)
        }
    }
}

private fun Location.toFix(): LocationFix = LocationFix(
    eventId = UUID.randomUUID().toString(),
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracy.toDouble(),
    altitudeMeters = if (hasAltitude()) altitude else null,
    speedMetersPerSecond = if (hasSpeed()) speed.toDouble() else null,
    bearingDegrees = if (hasBearing()) bearing.toDouble() else null,
    provider = provider?.takeIf { it.isNotBlank() } ?: "fused",
    isMock = LocationCompat.isMock(this),
    recordedAtMillis = time,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
)
