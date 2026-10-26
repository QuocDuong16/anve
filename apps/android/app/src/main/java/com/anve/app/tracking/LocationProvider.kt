package com.anve.app.tracking

import android.content.Context
import android.location.Location
import android.os.Looper
import androidx.core.location.LocationCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
}

/**
 * Fused provider implementation. It never calls `getLastLocation()`: a
 * cached fix is not realtime, and presenting one as current is exactly the
 * mistake the policy is designed to avoid.
 */
class FusedLocationProvider(context: Context) : LocationProvider {

    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    override fun isAvailable(): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS

    override fun updates(options: LocationRequestOptions): Flow<LocationFix> = callbackFlow {
        val priority = if (options.highAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = LocationRequest.Builder(priority, options.intervalMillis)
            .setMinUpdateDistanceMeters(options.minDistanceMeters)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    trySend(location.toFix())
                }
            }
        }

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { error ->
                    close(error)
                }
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }

        awaitClose {
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
