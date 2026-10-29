package com.anve.app.tracking

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Durable, non-coordinate tracking state:
 *
 * - [trackingRequested] is the user's intent. It survives process death so
 *   the UI can offer an honest resume instead of pretending tracking
 *   restarted (Android background-start rules apply).
 * - [pendingConsentRevocation] is written *before* the network call that
 *   turns sharing off. The sync worker refuses to upload anything while it
 *   is set and delivers the revocation first once connectivity returns.
 * - [localConsentConfirmed] is the last server-confirmed consent state. It
 *   lets an offline device show the right switch without claiming the
 *   server has seen a newer change.
 * - [lastSuccessfulUploadAtMillis] and [lastSyncErrorCode] are diagnostics;
 *   neither contains coordinates.
 */
data class LocalTrackingState(
    val trackingRequested: Boolean = false,
    val pendingConsentRevocation: Boolean = false,
    val localConsentConfirmed: Boolean = false,
    /**
     * The account the consent mirror belongs to. A session for another
     * account must not inherit it, even if a sign-out was interrupted.
     */
    val consentOwnerUserId: String? = null,
    val lastSuccessfulUploadAtMillis: Long? = null,
    val lastSyncErrorCode: String? = null,
)

interface TrackingStore {
    val state: Flow<LocalTrackingState>
    suspend fun current(): LocalTrackingState
    suspend fun setTrackingRequested(requested: Boolean)
    suspend fun setPendingConsentRevocation(pending: Boolean)
    suspend fun setLocalConsentConfirmed(confirmed: Boolean)
    suspend fun setConsentOwner(ownerUserId: String?)
    suspend fun setLastSuccessfulUpload(atMillis: Long)
    suspend fun setLastSyncError(code: String?)
}

private val Context.trackingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "anve_tracking",
)

/**
 * Preferences DataStore lives in `files/datastore`, which both backup rule
 * sets exclude. The queue's coordinates live in Room; this file only holds
 * intent/diagnostic flags.
 */
class DataStoreTrackingStore(context: Context) : TrackingStore {

    private val dataStore = context.applicationContext.trackingDataStore

    override val state: Flow<LocalTrackingState> = dataStore.data.map { prefs ->
        LocalTrackingState(
            trackingRequested = prefs[KEY_TRACKING_REQUESTED] ?: false,
            pendingConsentRevocation = prefs[KEY_PENDING_CONSENT_REVOCATION] ?: false,
            localConsentConfirmed = prefs[KEY_LOCAL_CONSENT_CONFIRMED] ?: false,
            consentOwnerUserId = prefs[KEY_CONSENT_OWNER],
            lastSuccessfulUploadAtMillis = prefs[KEY_LAST_UPLOAD_AT],
            lastSyncErrorCode = prefs[KEY_LAST_SYNC_ERROR],
        )
    }

    override suspend fun current(): LocalTrackingState =
        try {
            state.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A corrupt preferences file must not prevent starting the app.
            LocalTrackingState()
        }

    override suspend fun setTrackingRequested(requested: Boolean) {
        dataStore.edit { it[KEY_TRACKING_REQUESTED] = requested }
    }

    override suspend fun setPendingConsentRevocation(pending: Boolean) {
        dataStore.edit { it[KEY_PENDING_CONSENT_REVOCATION] = pending }
    }

    override suspend fun setLocalConsentConfirmed(confirmed: Boolean) {
        dataStore.edit { it[KEY_LOCAL_CONSENT_CONFIRMED] = confirmed }
    }

    override suspend fun setConsentOwner(ownerUserId: String?) {
        dataStore.edit { prefs ->
            if (prefs[KEY_CONSENT_OWNER] != ownerUserId) prefs.remove(KEY_LAST_SYNC_ERROR)
            if (ownerUserId == null) {
                prefs.remove(KEY_CONSENT_OWNER)
            } else {
                prefs[KEY_CONSENT_OWNER] = ownerUserId
            }
        }
    }

    override suspend fun setLastSuccessfulUpload(atMillis: Long) {
        dataStore.edit {
            it[KEY_LAST_UPLOAD_AT] = atMillis
            if (it[KEY_LAST_SYNC_ERROR] !in setOf("LOCATION_CAPTURE_GENERATION_EXPIRED", "LOCATION_HISTORY_DELETED")) it.remove(KEY_LAST_SYNC_ERROR)
        }
    }

    override suspend fun setLastSyncError(code: String?) {
        dataStore.edit { prefs ->
            if (code == null) prefs.remove(KEY_LAST_SYNC_ERROR) else prefs[KEY_LAST_SYNC_ERROR] = code
        }
    }

    private companion object {
        val KEY_TRACKING_REQUESTED = booleanPreferencesKey("tracking_requested")
        val KEY_PENDING_CONSENT_REVOCATION = booleanPreferencesKey("pending_consent_revocation")
        val KEY_LOCAL_CONSENT_CONFIRMED = booleanPreferencesKey("local_consent_confirmed")
        val KEY_CONSENT_OWNER = stringPreferencesKey("consent_owner_user_id")
        val KEY_LAST_UPLOAD_AT = longPreferencesKey("last_successful_upload_at")
        val KEY_LAST_SYNC_ERROR = stringPreferencesKey("last_sync_error")
    }
}
