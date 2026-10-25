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
 * Durable session storage. Implementations must keep tokens encrypted at
 * rest (ADR 0002) — the DataStore implementation seals the JSON blob with
 * [TokenCipher] before it ever reaches the file system.
 */
interface SessionStorage {
    val session: Flow<StoredSession?>
    suspend fun current(): StoredSession?
    suspend fun save(session: StoredSession)
    suspend fun clear()
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

    private fun decode(blob: String?): StoredSession? {
        if (blob.isNullOrBlank()) return null
        return try {
            val parts = blob.split(SEPARATOR)
            if (parts.size != 2) return null
            val sealed = SealedBytes(
                Base64.getDecoder().decode(parts[0]),
                Base64.getDecoder().decode(parts[1]),
            )
            adapter.fromJson(String(cipher.decrypt(sealed), Charsets.UTF_8))
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
        private const val SEPARATOR = "."
    }
}
