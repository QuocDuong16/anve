package com.anve.app.data.journey

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

private val Context.journeyDataStore: DataStore<Preferences> by preferencesDataStore(name = "anve_journey_outbox")

/** An admitted CREATE is reconciled by ID, never automatically re-posted. */
enum class JourneyAction { CREATE, ARRIVE, END, CANCEL, REVOKE_RECIPIENT }

/** No destination or coordinates are needed to reconcile an uncertain request. */
@JsonClass(generateAdapter = false)
data class JourneyOperation(
    val operationId: String = UUID.randomUUID().toString(),
    val owner: String,
    val journeyId: String,
    val action: JourneyAction,
    val createdAtMillis: Long,
    val recipient: String? = null,
)

interface JourneyOutbox {
    suspend fun read(owner: String): List<JourneyOperation>
    suspend fun put(operation: JourneyOperation)
    suspend fun remove(owner: String, operationId: String)
}

/** Encrypted, bounded, compare-by-operation removal protects newer stop intents. */
class EncryptedJourneyOutbox(
    context: Context,
    private val cipher: TokenCipher = KeystoreTokenCipher("anve_journey_outbox_key"),
) : JourneyOutbox {
    private val store = context.applicationContext.journeyDataStore
    private val key = stringPreferencesKey("sealed_operations")
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter<List<JourneyOperation>>(Types.newParameterizedType(List::class.java, JourneyOperation::class.java))

    private fun decode(value: String?): List<JourneyOperation> {
        if (value == null) return emptyList()
        val parts = value.split('.')
        require(parts.size == 2) { "Journey storage unavailable" }
        val bytes = cipher.decrypt(SealedBytes(Base64.getDecoder().decode(parts[0]), Base64.getDecoder().decode(parts[1])))
        return requireNotNull(adapter.fromJson(bytes.toString(Charsets.UTF_8)))
    }

    private fun encode(items: List<JourneyOperation>): String {
        val sealed = cipher.encrypt(adapter.toJson(items).toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sealed.ciphertext) + "." + Base64.getEncoder().encodeToString(sealed.iv)
    }

    override suspend fun read(owner: String): List<JourneyOperation> = decode(store.data.first()[key]).filter { it.owner == owner }

    override suspend fun put(operation: JourneyOperation) {
        UUID.fromString(operation.operationId)
        UUID.fromString(operation.owner)
        UUID.fromString(operation.journeyId)
        operation.recipient?.let(UUID::fromString)
        require(operation.action != JourneyAction.REVOKE_RECIPIENT || operation.recipient != null)
        store.edit { prefs ->
            val original = decode(prefs[key])
            require(operation.action != JourneyAction.CREATE || original.none { it.owner == operation.owner }) { "Journey operation still pending" }
            val previous = original.firstOrNull { it.owner == operation.owner && it.journeyId == operation.journeyId }
            require(previous == null || operation.action == JourneyAction.CANCEL) { "Journey operation still pending" }
            val items = original.filterNot { it.owner == operation.owner && it.journeyId == operation.journeyId }
            require(items.size < 20) { "Journey storage full" }
            prefs[key] = encode(items + operation)
        }
    }

    override suspend fun remove(owner: String, operationId: String) {
        store.edit { prefs ->
            prefs[key] = encode(decode(prefs[key]).filterNot { it.owner == owner && it.operationId == operationId })
        }
    }
}
