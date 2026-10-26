package com.anve.app.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/** A snapshot of the OS state relevant to tracking. */
data class PermissionSnapshot(
    val location: LocationPermissionState,
    val backgroundLocation: Boolean,
    val notifications: Boolean,
    val locationServicesEnabled: Boolean,
) {
    val canTrack: Boolean
        get() = location == LocationPermissionState.GRANTED_PRECISE ||
            location == LocationPermissionState.GRANTED_APPROXIMATE
}

interface TrackingPermissions {
    fun snapshot(): PermissionSnapshot
}

/**
 * Android permission/location state, read from the OS every time it is
 * needed. Nothing is cached: permissions can be revoked while the app runs,
 * and a cached boolean is how a tracker keeps believing it may collect.
 */
class AndroidLocationPermissions(context: Context) : TrackingPermissions {

    private val appContext = context.applicationContext

    override fun snapshot(): PermissionSnapshot = PermissionSnapshot(
        location = permissionState(),
        backgroundLocation = hasBackgroundLocation(),
        notifications = hasNotifications(),
        locationServicesEnabled = locationServicesEnabled(),
    )

    fun permissionState(): LocationPermissionState {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return when {
            fine -> LocationPermissionState.GRANTED_PRECISE
            coarse -> LocationPermissionState.GRANTED_APPROXIMATE
            // A fresh install and a denied permission are indistinguishable
            // at the OS level; the UI treats both as "ask with disclosure".
            else -> LocationPermissionState.DENIED
        }
    }

    fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS)

    /** Whether the device's location switch is on (not just the app permission). */
    fun locationServicesEnabled(): Boolean {
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                @Suppress("DEPRECATION")
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}
