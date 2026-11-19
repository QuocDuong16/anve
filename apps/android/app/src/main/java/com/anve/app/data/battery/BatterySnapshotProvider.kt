package com.anve.app.data.battery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.anve.app.tracking.LocationFix
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

interface BatterySnapshotProvider {
    suspend fun captureFreshFix(): LocationFix?
}

object NoOpBatterySnapshotProvider : BatterySnapshotProvider {
    override suspend fun captureFreshFix(): LocationFix? = null
}

/**
 * Best-effort single-shot location capture used only after active tracking
 * passes the 15% or 5% low-battery threshold without a <30s fix. Times out
 * after 4 seconds so low-battery notifications fail open without coordinates.
 */
class AndroidBatterySnapshotProvider(context: Context) : BatterySnapshotProvider {
    private val appContext = context.applicationContext
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(appContext) }

    override suspend fun captureFreshFix(): LocationFix? {
        val hasFine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null

        val gmsStatus = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
        }.getOrDefault(ConnectionResult.SERVICE_MISSING)
        if (gmsStatus != ConnectionResult.SUCCESS) return null

        val priority = if (hasFine) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val location = withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MILLIS) {
            fetchCurrentLocation(priority)
        } ?: return null

        if (LocationCompat.isMock(location)) return null
        val lat = location.latitude
        val lon = location.longitude
        val acc = location.accuracy.toDouble()
        if (!lat.isFinite() || lat !in -90.0..90.0 ||
            !lon.isFinite() || lon !in -180.0..180.0 ||
            !acc.isFinite() || acc < 0.0 || acc > 100_000.0
        ) {
            return null
        }

        val recordedAtMillis = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        return LocationFix(
            eventId = UUID.randomUUID().toString(),
            latitude = lat,
            longitude = lon,
            accuracyMeters = acc,
            altitudeMeters = if (location.hasAltitude()) location.altitude else null,
            speedMetersPerSecond = if (location.hasSpeed()) location.speed.toDouble() else null,
            bearingDegrees = if (location.hasBearing()) location.bearing.toDouble() else null,
            provider = location.provider?.takeIf { it.isNotBlank() } ?: "fused",
            isMock = false,
            recordedAtMillis = recordedAtMillis,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
        )
    }

    private suspend fun fetchCurrentLocation(priority: Int): Location? = suspendCancellableCoroutine { cont ->
        val tokenSource = CancellationTokenSource()
        cont.invokeOnCancellation { tokenSource.cancel() }
        val request = CurrentLocationRequest.Builder()
            .setPriority(priority)
            .setMaxUpdateAgeMillis(0L)
            .setDurationMillis(CURRENT_LOCATION_TIMEOUT_MILLIS)
            .build()
        try {
            fusedClient.getCurrentLocation(request, tokenSource.token)
                .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            if (cont.isActive) cont.resume(null)
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    companion object {
        const val CURRENT_LOCATION_TIMEOUT_MILLIS: Long = 4_000L
    }
}
