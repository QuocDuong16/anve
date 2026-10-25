package com.anve.app.data.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.anve.app.BuildConfig
import com.anve.app.data.api.DeviceRegistrationDto
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.deviceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "anve_device",
)

/** Supplies the device registration sent with every sign-in. */
interface DeviceRegistrationProvider {
    suspend fun registration(): DeviceRegistrationDto
}

/**
 * The app-owned device identifier (ADR 0002): a random UUID generated once
 * and kept in private storage. It lets the server reuse one `devices` row
 * for the same physical phone across reinstalls and re-logins.
 */
class DeviceIdentity(private val context: Context) : DeviceRegistrationProvider {

    private val dataStore = context.applicationContext.deviceDataStore

    override suspend fun registration(): DeviceRegistrationDto {
        val id = deviceId()
        return DeviceRegistrationDto(
            clientDeviceId = id,
            name = android.os.Build.MODEL ?: "Thiết bị Android",
            platform = "android",
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    suspend fun deviceId(): String {
        val existing = dataStore.data.first()[KEY_DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        dataStore.edit { prefs -> prefs[KEY_DEVICE_ID] = generated }
        return generated
    }

    companion object {
        private val KEY_DEVICE_ID = stringPreferencesKey("client_device_id")
    }
}
