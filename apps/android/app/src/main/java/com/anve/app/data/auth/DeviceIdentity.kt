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

/** Durable storage of the app installation id (DataStore in production). */
interface InstallationIdStore {
    suspend fun read(): String?
    suspend fun write(id: String)
}

private class DataStoreInstallationIdStore(
    private val dataStore: DataStore<Preferences>,
) : InstallationIdStore {
    override suspend fun read(): String? = dataStore.data.first()[KEY_DEVICE_ID]

    override suspend fun write(id: String) {
        dataStore.edit { prefs -> prefs[KEY_DEVICE_ID] = id }
    }

    private companion object {
        val KEY_DEVICE_ID = stringPreferencesKey("client_device_id")
    }
}

/**
 * The AnVề app installation identity (ADR 0002/0004): a random UUID created
 * on first use and kept in private storage.
 *
 * Semantics, deliberately narrow:
 *  - stable across app restarts, process death, app updates and
 *    sign-out/sign-in (session storage is a different file);
 *  - regenerated after a reinstall or a factory reset, because private app
 *    storage is deleted with the app;
 *  - it is an *installation* identity, not a hardware identity. No Android
 *    hardware identifier is read, so two installs on one phone are two
 *    devices and one install restored from backup is still a new install.
 *
 * The server keys its devices row on (user, client_device_id) and treats it
 * the same way: reusing one row while the installation exists, creating a
 * new one after a reinstall.
 */
class DeviceIdentity(
    private val idStore: InstallationIdStore,
    private val deviceName: () -> String = ::defaultDeviceName,
) : DeviceRegistrationProvider {

    /** Production wiring: the id lives in the app's DataStore. */
    constructor(context: Context) : this(
        DataStoreInstallationIdStore(context.applicationContext.deviceDataStore),
    )

    override suspend fun registration(): DeviceRegistrationDto {
        val id = deviceId()
        return DeviceRegistrationDto(
            clientDeviceId = id,
            name = deviceName(),
            platform = "android",
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    suspend fun deviceId(): String {
        val existing = idStore.read()
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        idStore.write(generated)
        return generated
    }
}

private fun defaultDeviceName(): String =
    android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Thiết bị Android"
