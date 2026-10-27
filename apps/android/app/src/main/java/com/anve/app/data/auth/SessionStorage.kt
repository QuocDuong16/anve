package com.anve.app.data.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Base64
import java.util.UUID

/** The session tokens as persisted on the device, before encryption. */
@JsonClass(generateAdapter = false)
data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: String,
    val refreshExpiresAt: String,
    /**
     * The server account id this session belongs to. The offline location
     * queue is scoped by it, so queued points can never be uploaded by a
     * different account. Null only for sessions persisted by pre-M3 builds.
     */
    val userId: String? = null,
    val locationSharingEnabled: Boolean? = null,
    val locationSharingVersion: Long? = null,
)

/**
 * A server-side revocation that a local sign-out could not deliver because
 * the backend was unreachable. It is never usable as a session: it only
 * feeds a best-effort retry that revokes the old session once the network
 * allows it.
 *
 * Records are independent of each other: several accounts can sign out
 * offline on the same install without overwriting each other's revocation.
 * [expiresAtMillis] bounds the retry window from the moment of sign-out, so
 * a refresh token that rotates on retry keeps its original deadline.
 * The queue never stores an email, display name or any other account
 * metadata — [id] is a random local identifier.
 */
@JsonClass(generateAdapter = false)
data class PendingRevocation(
    val id: String,
    val refreshToken: String,
    val recordedAtMillis: Long,
    val expiresAtMillis: Long,
    val attemptCount: Int = 0,
    val nextAttemptAtMillis: Long = 0,
    val ownerUserId: String? = null,
)

/** Durable consent intent, encrypted at rest and scoped to its owner. */
@JsonClass(generateAdapter = false)
data class PendingConsentOperation(
    val operationId: String,
    val ownerUserId: String,
    val desiredEnabled: Boolean,
    val expectedVersion: Long,
    val generation: Long,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val refreshToken: String? = null,
    val credentialExpiresAtMillis: Long? = null,
    val attemptCount: Int = 0,
    val nextAttemptAtMillis: Long = 0,
    val consentConfirmed: Boolean = false,
    val signOutAfterConsent: Boolean = false,
    val requiresOwnerAuthentication: Boolean = false,
    val lastErrorCode: String? = null,
    /** Non-null only when a newer authoritative server choice conflicts with this operation. */
    val conflictServerEnabled: Boolean? = null,
    val conflictServerVersion: Long? = null,
    val conflictObservedAtMillis: Long? = null,
)

val PendingConsentOperation.hasConsentConflict: Boolean
    get() = conflictServerEnabled != null && conflictServerVersion != null

/** Retry window for an undelivered sign-out; older records are dropped. */
const val PENDING_REVOCATION_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

/**
 * Upper bound of the durable queue. More simultaneous offline sign-outs than
 * this on one install is not a realistic case, and an unbounded queue would
 * be a storage-growth bug. When the bound is exceeded the oldest record is
 * dropped; the best-effort nature of the retry is documented.
 */
const val MAX_PENDING_REVOCATIONS: Int = 8
const val PENDING_CONSENT_OPERATION_TTL_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
const val PENDING_CONSENT_CREDENTIAL_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

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

    /**
     * Records the account id on the session without touching its tokens.
     * This must not be implemented as read-copy-save: a concurrent token
     * rotation between the read and the write would resurrect a dead token.
     */
    suspend fun setUserId(userId: String)

    suspend fun setUserIdIfMatches(accessToken: String, userId: String): Boolean

    /** Adds (or replaces, by id) an undelivered revocation, sealed at rest. */
    suspend fun savePendingRevocation(pending: PendingRevocation)

    /** Returns every unexpired pending revocation, oldest first. */
    suspend fun pendingRevocations(): List<PendingRevocation>

    /** Removes a record once the server confirmed the revocation or death. */
    suspend fun removePendingRevocation(id: String)

    /** Atomically records the server's authoritative consent snapshot for the matching owner. */
    suspend fun updateConsent(ownerUserId: String, enabled: Boolean, version: Long): Boolean

    /** Adds or replaces the latest consent operation for this owner. */
    suspend fun savePendingConsentOperation(operation: PendingConsentOperation)

    /** Reads unexpired owner-scoped operations and strips expired credentials. */
    suspend fun pendingConsentOperations(): List<PendingConsentOperation>

    suspend fun removePendingConsentOperation(operationId: String)

    suspend fun removePendingConsentOperations(ownerUserId: String)

    /** Session mutations must not clear or rotate a different account's newer session. */
    suspend fun clearIfMatches(ownerUserId: String?, accessToken: String): Boolean

    suspend fun rotateIfMatches(ownerUserId: String?, accessToken: String, session: StoredSession): Boolean
}

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "anve_session",
)

/** Production storage: DataStore + Keystore-sealed blobs. */
class DataStoreSessionStorage(
    context: Context,
    private val cipher: TokenCipher = KeystoreTokenCipher(),
    moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
    private val now: () -> Long = System::currentTimeMillis,
) : SessionStorage {

    private val dataStore = context.applicationContext.sessionDataStore
    private val adapter = moshi.adapter(StoredSession::class.java)
    private val pendingAdapter: JsonAdapter<List<PendingRevocation>> = moshi.adapter(
        Types.newParameterizedType(List::class.java, PendingRevocation::class.java),
    )
    private val consentAdapter: JsonAdapter<List<PendingConsentOperation>> = moshi.adapter(
        Types.newParameterizedType(List::class.java, PendingConsentOperation::class.java),
    )
    private val legacyPendingAdapter = moshi.adapter(LegacyPendingRevocation::class.java)

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

    override suspend fun clearIfMatches(ownerUserId: String?, accessToken: String): Boolean {
        var cleared = false
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_SESSION_BLOB]) ?: return@edit
            if (current.userId == ownerUserId && current.accessToken == accessToken) {
                prefs.remove(KEY_SESSION_BLOB)
                cleared = true
            }
        }
        return cleared
    }

    override suspend fun rotateIfMatches(
        ownerUserId: String?,
        accessToken: String,
        session: StoredSession,
    ): Boolean {
        var rotated = false
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_SESSION_BLOB]) ?: return@edit
            if (current.userId == ownerUserId && current.accessToken == accessToken) {
                val sealed = cipher.encrypt(adapter.toJson(session).toByteArray(Charsets.UTF_8))
                prefs[KEY_SESSION_BLOB] = encode(sealed)
                rotated = true
            }
        }
        return rotated
    }

    override suspend fun setUserId(userId: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SESSION_BLOB]?.let { decode(it) } ?: return@edit
            val sealed = cipher.encrypt(
                adapter.toJson(current.copy(userId = userId)).toByteArray(Charsets.UTF_8),
            )
            prefs[KEY_SESSION_BLOB] = encode(sealed)
        }
    }

    override suspend fun setUserIdIfMatches(accessToken: String, userId: String): Boolean {
        var updated = false
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_SESSION_BLOB]) ?: return@edit
            if (current.accessToken != accessToken || current.userId != null) return@edit
            val sealed = cipher.encrypt(
                adapter.toJson(current.copy(userId = userId)).toByteArray(Charsets.UTF_8),
            )
            prefs[KEY_SESSION_BLOB] = encode(sealed)
            updated = true
        }
        return updated
    }

    override suspend fun updateConsent(ownerUserId: String, enabled: Boolean, version: Long): Boolean {
        var updated = false
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_SESSION_BLOB]) ?: return@edit
            if (current.userId != ownerUserId || version < (current.locationSharingVersion ?: -1L)) return@edit
            val sealed = cipher.encrypt(
                adapter.toJson(current.copy(
                    locationSharingEnabled = enabled,
                    locationSharingVersion = version,
                )).toByteArray(Charsets.UTF_8),
            )
            prefs[KEY_SESSION_BLOB] = encode(sealed)
            updated = true
        }
        return updated
    }

    /**
     * The whole queue is one sealed JSON array, so reading, merging and
     * writing happen inside a single DataStore edit: two concurrent
     * sign-outs cannot drop each other's record, and a crash leaves either
     * the old or the new queue — never a partially written one.
     */
    override suspend fun savePendingRevocation(pending: PendingRevocation) {
        dataStore.edit { prefs ->
            val merged = mergePendingRevocation(readQueue(prefs), pending, now())
            writeQueue(prefs, merged)
            prefs.remove(KEY_LEGACY_PENDING_REVOCATION)
        }
    }

    override suspend fun pendingRevocations(): List<PendingRevocation> {
        return try {
            var records = emptyList<PendingRevocation>()
            dataStore.edit { prefs ->
                records = prunePendingRevocations(readQueue(prefs), now())
                // Persist the queue once so a legacy single-record entry is
                // migrated (and expired entries are dropped) even when no
                // retry or sign-out follows in this process.
                writeQueue(prefs, records)
                prefs.remove(KEY_LEGACY_PENDING_REVOCATION)
            }
            records
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Corrupt or unreadable storage must not prevent the app from
            // starting; there is simply nothing to retry.
            emptyList()
        }
    }

    override suspend fun removePendingRevocation(id: String) {
        dataStore.edit { prefs ->
            val remaining = prunePendingRevocations(
                readQueue(prefs).filterNot { it.id == id },
                now(),
            )
            writeQueue(prefs, remaining)
            prefs.remove(KEY_LEGACY_PENDING_REVOCATION)
        }
    }

    override suspend fun savePendingConsentOperation(operation: PendingConsentOperation) {
        dataStore.edit { prefs ->
            val merged = mergePendingConsentOperation(readConsentQueue(prefs), operation, now())
            writeConsentQueue(prefs, merged)
        }
    }

    override suspend fun pendingConsentOperations(): List<PendingConsentOperation> {
        var records = emptyList<PendingConsentOperation>()
        dataStore.edit { prefs ->
            records = prunePendingConsentOperations(readConsentQueue(prefs), now())
            writeConsentQueue(prefs, records)
        }
        return records
    }

    override suspend fun removePendingConsentOperation(operationId: String) {
        dataStore.edit { prefs ->
            writeConsentQueue(
                prefs,
                readConsentQueue(prefs).filterNot { it.operationId == operationId },
            )
        }
    }

    override suspend fun removePendingConsentOperations(ownerUserId: String) {
        dataStore.edit { prefs ->
            writeConsentQueue(
                prefs,
                readConsentQueue(prefs).filterNot { it.ownerUserId == ownerUserId },
            )
        }
    }

    /**
     * Reads the queue, falling back to the single-record format written by
     * earlier builds. The legacy id is derived from its timestamp so a
     * removal issued before any migration still matches.
     */
    private fun readQueue(prefs: Preferences): List<PendingRevocation> {
        decodeQueue(prefs[KEY_PENDING_REVOCATIONS]).let { queue ->
            if (queue.isNotEmpty()) return queue
        }
        val legacy = decodeLegacy(prefs[KEY_LEGACY_PENDING_REVOCATION]) ?: return emptyList()
        return listOf(
            PendingRevocation(
                id = legacyId(legacy.recordedAtMillis),
                refreshToken = legacy.refreshToken,
                recordedAtMillis = legacy.recordedAtMillis,
                expiresAtMillis = legacy.recordedAtMillis + PENDING_REVOCATION_TTL_MILLIS,
            ),
        )
    }

    private fun writeQueue(prefs: MutablePreferences, records: List<PendingRevocation>) {
        if (records.isEmpty()) {
            prefs.remove(KEY_PENDING_REVOCATIONS)
            return
        }
        val sealed = cipher.encrypt(
            pendingAdapter.toJson(records).toByteArray(Charsets.UTF_8),
        )
        prefs[KEY_PENDING_REVOCATIONS] = encode(sealed)
    }

    private fun readConsentQueue(prefs: Preferences): List<PendingConsentOperation> =
        decryptBlob(prefs[KEY_PENDING_CONSENT_OPERATIONS])?.let { plain ->
            runCatching { consentAdapter.fromJson(String(plain, Charsets.UTF_8)).orEmpty() }
                .getOrDefault(emptyList())
        }.orEmpty()

    private fun writeConsentQueue(prefs: MutablePreferences, records: List<PendingConsentOperation>) {
        if (records.isEmpty()) {
            prefs.remove(KEY_PENDING_CONSENT_OPERATIONS)
            return
        }
        val sealed = cipher.encrypt(consentAdapter.toJson(records).toByteArray(Charsets.UTF_8))
        prefs[KEY_PENDING_CONSENT_OPERATIONS] = encode(sealed)
    }

    private fun decodeQueue(blob: String?): List<PendingRevocation> {
        val plain = decryptBlob(blob) ?: return emptyList()
        return try {
            pendingAdapter.fromJson(String(plain, Charsets.UTF_8)).orEmpty()
        } catch (_: Exception) {
            // A corrupted queue must not block sign-out or startup; the
            // records are unrecoverable and simply disappear.
            emptyList()
        }
    }

    private fun decodeLegacy(blob: String?): LegacyPendingRevocation? {
        val plain = decryptBlob(blob) ?: return null
        return try {
            legacyPendingAdapter.fromJson(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(blob: String?): StoredSession? {
        val plain = decryptBlob(blob) ?: return null
        return try {
            adapter.fromJson(String(plain, Charsets.UTF_8))
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

    private companion object {
        val KEY_SESSION_BLOB = stringPreferencesKey("session_blob")
        val KEY_PENDING_REVOCATIONS = stringPreferencesKey("pending_revocations")
        val KEY_PENDING_CONSENT_OPERATIONS = stringPreferencesKey("pending_consent_operations")
        val KEY_LEGACY_PENDING_REVOCATION = stringPreferencesKey("pending_revocation")
        const val SEPARATOR = "."
    }
}

/** The single-record format written by builds before the queue existed. */
@JsonClass(generateAdapter = false)
private data class LegacyPendingRevocation(
    val refreshToken: String,
    val recordedAtMillis: Long,
)

private fun legacyId(recordedAtMillis: Long): String = "legacy-$recordedAtMillis"

/** A revoked token must never be retried after this point. */
internal fun isExpired(pending: PendingRevocation, nowMillis: Long): Boolean =
    nowMillis >= pending.expiresAtMillis

/**
 * Drops expired records and keeps the newest [max]. Pure so the bound and
 * expiry rules are testable without Android storage.
 */
internal fun prunePendingRevocations(
    records: List<PendingRevocation>,
    nowMillis: Long,
    max: Int = MAX_PENDING_REVOCATIONS,
): List<PendingRevocation> =
    records
        .filterNot { isExpired(it, nowMillis) }
        .sortedBy { it.recordedAtMillis }
        .takeLast(max.coerceAtLeast(1))

/**
 * Inserts or replaces [pending] by id, then applies expiry and the bound.
 * Replacing by id is what keeps a rotated retry record from duplicating.
 */
internal fun mergePendingRevocation(
    records: List<PendingRevocation>,
    pending: PendingRevocation,
    nowMillis: Long,
    max: Int = MAX_PENDING_REVOCATIONS,
): List<PendingRevocation> =
    prunePendingRevocations(
        records.filterNot { it.id == pending.id } + pending,
        nowMillis,
        max,
    )

/** Creates a fresh queue record for a local sign-out. */
internal fun newPendingRevocation(
    refreshToken: String,
    nowMillis: Long,
    id: String = UUID.randomUUID().toString(),
    ownerUserId: String? = null,
): PendingRevocation = PendingRevocation(
    id = id,
    refreshToken = refreshToken,
    recordedAtMillis = nowMillis,
    expiresAtMillis = nowMillis + PENDING_REVOCATION_TTL_MILLIS,
    ownerUserId = ownerUserId,
)

/** Expires the credential before the operation metadata; no secret is retained past its TTL. */
internal fun prunePendingConsentOperations(
    operations: List<PendingConsentOperation>,
    nowMillis: Long,
): List<PendingConsentOperation> = operations
    .map { operation ->
        when {
            nowMillis >= operation.expiresAtMillis -> operation.copy(
                refreshToken = null,
                credentialExpiresAtMillis = null,
                requiresOwnerAuthentication = true,
                lastErrorCode = "CONSENT_OPERATION_EXPIRED",
            )
            operation.refreshToken != null &&
                operation.credentialExpiresAtMillis?.let { nowMillis >= it } == true -> operation.copy(
                refreshToken = null,
                credentialExpiresAtMillis = null,
                requiresOwnerAuthentication = true,
                lastErrorCode = "REVOCATION_CREDENTIAL_EXPIRED",
            )
            else -> operation
        }
    }
    .sortedBy { it.createdAtMillis }

/** One latest durable consent intent per account; cap metadata growth across accounts. */
internal fun mergePendingConsentOperation(
    operations: List<PendingConsentOperation>,
    operation: PendingConsentOperation,
    nowMillis: Long,
): List<PendingConsentOperation> = prunePendingConsentOperations(
    operations.filterNot { it.ownerUserId == operation.ownerUserId } + operation,
    nowMillis,
)

internal fun newPendingConsentOperation(
    ownerUserId: String,
    desiredEnabled: Boolean,
    expectedVersion: Long,
    generation: Long,
    nowMillis: Long,
    operationId: String = UUID.randomUUID().toString(),
): PendingConsentOperation = PendingConsentOperation(
    operationId = operationId,
    ownerUserId = ownerUserId,
    desiredEnabled = desiredEnabled,
    expectedVersion = expectedVersion,
    generation = generation,
    createdAtMillis = nowMillis,
    expiresAtMillis = nowMillis + PENDING_CONSENT_OPERATION_TTL_MILLIS,
)
