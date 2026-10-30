package com.anve.app.data.sos

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.anve.app.data.auth.KeystoreTokenCipher
import com.anve.app.data.auth.SealedBytes
import com.anve.app.data.auth.TokenCipher
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.first

private val Context.sosDataStore: DataStore<Preferences> by preferencesDataStore(name = "anve_sos_outbox")
private const val MAX_SOS_OUTBOX = 20

/** UNKNOWN means a request may have committed; it is not a success claim. */
enum class SosLocalState { QUEUED, SENDING, UNKNOWN, CONFIRMED, CANCEL_REQUESTED, CANCELLED, RESOLVED, EXPIRED, SERVER_EXPIRED }

@JsonClass(generateAdapter = false)
data class SosOutboxItem(
    val eventId: String,
    val ownerId: String,
    val recordedAt: String,
    val recipientIds: List<String>,
    val createdAtMillis: Long,
    val state: SosLocalState,
    val cancelRequestedAtMillis: Long? = null,
)

@JsonClass(generateAdapter = false)
data class SosCachedMember(val ownerId: String, val userId: String, val displayName: String, val savedAtMillis: Long)

/** Atomic, owner-scoped durable operations; a full queue is never silently pruned. */
interface SosOutbox {
    suspend fun read(owner: String): List<SosOutboxItem>
    suspend fun enqueue(item: SosOutboxItem)
    suspend fun replace(owner: String, id: String, expected: SosLocalState, next: SosLocalState, cancelRequestedAtMillis: Long? = null): Boolean
    suspend fun removeFinished(owner: String, id: String): Boolean
    suspend fun cachedMembers(owner: String): List<SosCachedMember>
    suspend fun saveMembers(owner: String, members: List<SosCachedMember>)
}

/** Keystore-sealed DataStore; corrupt or undecryptable data is an error, never an empty outbox. */
class EncryptedSosOutbox(
    context: Context,
    private val cipher: TokenCipher = KeystoreTokenCipher("anve_sos_outbox_key"),
) : SosOutbox {
    private val dataStore = context.applicationContext.sosDataStore
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter<List<SosOutboxItem>>(Types.newParameterizedType(List::class.java, SosOutboxItem::class.java))
    private val memberAdapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter<List<SosCachedMember>>(Types.newParameterizedType(List::class.java, SosCachedMember::class.java))
    private val key = stringPreferencesKey("sealed_outbox")
    private val memberKey = stringPreferencesKey("sealed_members")

    private fun decode(blob: String?): List<SosOutboxItem> {
        if (blob == null) return emptyList()
        val parts = blob.split('.')
        require(parts.size == 2) { "SOS outbox cannot be read" }
        val plain = cipher.decrypt(SealedBytes(Base64.getDecoder().decode(parts[0]), Base64.getDecoder().decode(parts[1])))
        return requireNotNull(adapter.fromJson(plain.toString(Charsets.UTF_8))) { "SOS outbox cannot be read" }
    }

    private fun encode(items: List<SosOutboxItem>): String {
        val sealed = cipher.encrypt(adapter.toJson(items).toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sealed.ciphertext) + "." + Base64.getEncoder().encodeToString(sealed.iv)
    }

    override suspend fun read(owner: String): List<SosOutboxItem> = decode(dataStore.data.first()[key]).filter { it.ownerId == owner }

    override suspend fun enqueue(item: SosOutboxItem) {
        require(runCatching { UUID.fromString(item.eventId); UUID.fromString(item.ownerId) }.isSuccess)
        require(item.state == SosLocalState.QUEUED && item.recipientIds.isNotEmpty() && item.recipientIds.size <= 30)
        require(item.recipientIds.distinct().size == item.recipientIds.size)
        require(item.recipientIds.all { runCatching { UUID.fromString(it) }.isSuccess && it != item.ownerId })
        dataStore.edit { prefs ->
            val items = decode(prefs[key])
            require(items.size < MAX_SOS_OUTBOX) { "SOS outbox is full" }
            require(items.none { it.eventId == item.eventId }) { "Duplicate SOS event" }
            prefs[key] = encode(items + item)
        }
    }

    override suspend fun replace(owner: String, id: String, expected: SosLocalState, next: SosLocalState, cancelRequestedAtMillis: Long?): Boolean {
        var changed = false
        dataStore.edit { prefs ->
            val items = decode(prefs[key])
            val updated = items.map { item ->
                if (item.ownerId == owner && item.eventId == id && item.state == expected) {
                    changed = true
                    item.copy(state = next, cancelRequestedAtMillis = if (next == SosLocalState.CANCEL_REQUESTED) cancelRequestedAtMillis else null)
                } else item
            }
            if (changed) prefs[key] = encode(updated)
        }
        return changed
    }

    override suspend fun removeFinished(owner: String, id: String): Boolean {
        var removed = false
        dataStore.edit { prefs ->
            val items = decode(prefs[key])
            val updated = items.filterNot {
                val remove = it.ownerId == owner && it.eventId == id && it.state in setOf(SosLocalState.CANCELLED, SosLocalState.RESOLVED, SosLocalState.EXPIRED, SosLocalState.SERVER_EXPIRED)
                if (remove) removed = true
                remove
            }
            if (removed) prefs[key] = encode(updated)
        }
        return removed
    }

    override suspend fun cachedMembers(owner: String): List<SosCachedMember> {
        val blob = dataStore.data.first()[memberKey] ?: return emptyList()
        val parts = blob.split('.')
        require(parts.size == 2) { "SOS recipient cache cannot be read" }
        val plain = cipher.decrypt(SealedBytes(Base64.getDecoder().decode(parts[0]), Base64.getDecoder().decode(parts[1])))
        return requireNotNull(memberAdapter.fromJson(plain.toString(Charsets.UTF_8))).filter { it.ownerId == owner }
    }

    override suspend fun saveMembers(owner: String, members: List<SosCachedMember>) {
        require(members.size <= 30 && members.all { it.ownerId == owner })
        dataStore.edit { prefs ->
            val old = prefs[memberKey]?.let { blob ->
                val parts = blob.split('.')
                require(parts.size == 2) { "SOS recipient cache cannot be read" }
                val plain = cipher.decrypt(SealedBytes(Base64.getDecoder().decode(parts[0]), Base64.getDecoder().decode(parts[1])))
                requireNotNull(memberAdapter.fromJson(plain.toString(Charsets.UTF_8)))
            } ?: emptyList()
            val records = (old.filter { it.ownerId != owner } + members).sortedByDescending { it.savedAtMillis }.take(240)
            val sealed = cipher.encrypt(memberAdapter.toJson(records).toByteArray(Charsets.UTF_8))
            prefs[memberKey] = Base64.getEncoder().encodeToString(sealed.ciphertext) + "." + Base64.getEncoder().encodeToString(sealed.iv)
        }
    }
}
