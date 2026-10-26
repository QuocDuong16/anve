package com.anve.app.data.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Base64

/** The session tokens as persisted on the device, before encryption. */
@JsonClass(generateAdapter = false)
data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: String,
    val refreshExpiresAt: String,
)

/**
 * A server-side revocation that a local sign-out could not deliver because
 * the backend was unreachable. It is never usable as a session: it only
 * feeds a best-effort retry that revokes the old session once the network
 * allows it, and it expires after [PENDING_REVOCATION_TTL_MILLIS].
 */
@JsonClass(generateAdapter = false)
data class PendingRevocation(
    val refreshToken: String,
    val recordedAtMillis: Long,
)

/** Retry window for an undelivered sign-out; older records are dropped. */
const val PENDING_REVOCATION_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

/**
 * Durable session storage. Implementations must keep tokens encrypted at
 * rest (ADR 0002) — the DataStore implementation seals every blob with
 * [TokenCipher] before it ever reaches the file system.
 */
interface SessionStorage {
    val session: Flow<StoredSession?>
    suspend fun current(): StoredSession?
    suspend fun save(session: StoredSession)
    suspend fun clear()

    /** Records an undelivered server-side revocation (sealed at rest). */
    suspend fun savePendingRevocation(pending: PendingRevocation)

    /** Returns the pending revocation, or null when none or expired. */
    suspend fun pendingRevocation(): PendingRevocation?

    suspend fun clearPendingRevocation()
}

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "anve_session",
)

/** Production storage: DataStore + Keystore-sealed blob. */
class DataStoreSessionStorage(
    context: Context,
    private val cipher: TokenCipher = KeystoreTokenCipher(),
    moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
) : SessionStorage {

    private val dataStore = context.applicationContext.sessionDataStore
    private val adapter = moshi.adapter(StoredSession::class.java)
    private val pendingAdapter = moshi.adapter(PendingRevocation::class.java)

    override val session: Flow<StoredSession?> = dataStore.data.map { prefs ->
        decode(prefs[KEY_SESSION_BLOB])
    }

    override suspend fun current(): StoredSession? = session.first()

    override suspend fun save(session: StoredSession) {
        val sealed = cipher.encrypt(adapter.toJson(session).toByteArray(Charsets.UTF_8))
        dataStore.edit { prefs ->
            prefs[KEY_SESSION_BLOB] = encode(sealed)
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs -> prefs.remove(KEY_SESSION_BLOB) }
    }

    override suspend fun savePendingRevocation(pending: PendingRevocation) {
        val sealed = cipher.encrypt(pendingAdapter.toJson(pending).toByteArray(Charsets.UTF_8))
        dataStore.edit { prefs ->
            prefs[KEY_PENDING_REVOCATION] = encode(sealed)
        }
    }

    override suspend fun pendingRevocation(): PendingRevocation? {
        val pending = decodePending(dataStore.data.first()[KEY_PENDING_REVOCATION])
        if (pending != null && isExpired(pending, System.currentTimeMillis())) {
            clearPendingRevocation()
            return null
        }
        return pending
    }

    override suspend fun clearPendingRevocation() {
        dataStore.edit { prefs -> prefs.remove(KEY_PENDING_REVOCATION) }
    }

    private fun decode(blob: String?): StoredSession? {
        val plain = decryptBlob(blob) ?: return null
        return try {
            adapter.fromJson(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun decodePending(blob: String?): PendingRevocation? {
        val plain = decryptBlob(blob) ?: return null
        return try {
            pendingAdapter.fromJson(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptBlob(blob: String?): ByteArray? {
        if (blob.isNullOrBlank()) return null
        return try {
            val parts = blob.split(SEPARATOR)
            if (parts.size != 2) return null
            val sealed = SealedBytes(
                Base64.getDecoder().decode(parts[0]),
                Base64.getDecoder().decode(parts[1]),
            )
            cipher.decrypt(sealed)
        } catch (_: Exception) {
            // A blob that cannot be decrypted (key rotated away by an OS
            // update, corrupted file) is equivalent to "signed out".
            null
        }
    }

    private fun encode(sealed: SealedBytes): String =
        Base64.getEncoder().encodeToString(sealed.ciphertext) + SEPARATOR +
            Base64.getEncoder().encodeToString(sealed.iv)

    companion object {
        private val KEY_SESSION_BLOB = stringPreferencesKey("session_blob")
        private val KEY_PENDING_REVOCATION = stringPreferencesKey("pending_revocation")
        private const val SEPARATOR = "."
    }
}

internal fun isExpired(pending: PendingRevocation, nowMillis: Long): Boolean =
    nowMillis - pending.recordedAtMillis > PENDING_REVOCATION_TTL_MILLIS
