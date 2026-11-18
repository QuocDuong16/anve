package com.anve.app.data.sos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.anve.app.data.api.SosLocationDto
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class SosSnapshot(
    val location: SosLocationDto? = null,
    val batteryPercent: Int? = null,
)

interface SosSnapshotProvider {
    suspend fun capture(includeLocation: Boolean, includeBattery: Boolean): SosSnapshot
}

object NoOpSosSnapshotProvider : SosSnapshotProvider {
    override suspend fun capture(includeLocation: Boolean, includeBattery: Boolean): SosSnapshot = SosSnapshot()
}

/**
 * Best-effort single-shot location + battery reader for SOS requests.
 * Never starts background tracking and never blocks SOS submission on missing
 * permissions, Play Services unavailability, or indoor GPS timeouts.
 */
class AndroidSosSnapshotProvider(context: Context) : SosSnapshotProvider {
    private val appContext = context.applicationContext
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(appContext) }

    override suspend fun capture(includeLocation: Boolean, includeBattery: Boolean): SosSnapshot {
        val battery = if (includeBattery) readBatteryPercent() else null
        val location = if (includeLocation) readCurrentLocation() else null
        return SosSnapshot(location = location, batteryPercent = battery)
    }

    private fun readBatteryPercent(): Int? {
        val manager = appContext.getSystemService(BatteryManager::class.java)
        val capacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (capacity != null && capacity in 0..100) return capacity
        val status = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return ((level * 100f) / scale).toInt().coerceIn(0, 100)
    }

    private suspend fun readCurrentLocation(): SosLocationDto? {
        val hasFine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null
        val gmsStatus = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
        }.getOrDefault(ConnectionResult.SERVICE_MISSING)
        if (gmsStatus != ConnectionResult.SUCCESS) return null

        val priority = if (hasFine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val location = withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MILLIS) {
            fetchCurrentLocation(priority)
        } ?: return null

        val lat = location.latitude
        val lon = location.longitude
        val acc = location.accuracy.toDouble()
        if (!lat.isFinite() || lat !in -90.0..90.0 ||
            !lon.isFinite() || lon !in -180.0..180.0 ||
            !acc.isFinite() || acc < 0.0 || acc > 100_000.0
        ) {
            return null
        }
        val recordedAt = Instant.ofEpochMilli(location.time.takeIf { it > 0 } ?: System.currentTimeMillis()).toString()
        return SosLocationDto(
            latitude = lat,
            longitude = lon,
            horizontalAccuracyMeters = acc,
            recordedAt = recordedAt,
        )
    }

    private suspend fun fetchCurrentLocation(priority: Int): Location? = suspendCancellableCoroutine { cont ->
        val tokenSource = CancellationTokenSource()
        cont.invokeOnCancellation { tokenSource.cancel() }
        val request = CurrentLocationRequest.Builder()
            .setPriority(priority)
            .setMaxUpdateAgeMillis(MAX_LOCATION_AGE_MILLIS)
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

    private companion object {
        const val CURRENT_LOCATION_TIMEOUT_MILLIS = 4_000L
        const val MAX_LOCATION_AGE_MILLIS = 30_000L
    }
}
