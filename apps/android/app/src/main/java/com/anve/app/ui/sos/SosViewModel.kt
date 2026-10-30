package com.anve.app.ui.sos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.AppContainer
import com.anve.app.data.api.SosEventDto
import com.anve.app.data.api.SosRecipientDto
import com.anve.app.data.sos.SosCachedMember
import com.anve.app.data.sos.SosDataSource
import com.anve.app.data.sos.SosLocalState
import com.anve.app.data.sos.SosOutbox
import com.anve.app.data.sos.SosOutboxItem
import com.anve.app.data.sos.SosSyncEngine
import com.anve.app.data.sos.SosSyncScheduler
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private class SosLocalQueueException(cause: Throwable) : Exception(cause)

data class SosState(
    val owner: String? = null,
    val members: List<SosCachedMember> = emptyList(),
    val events: List<SosEventDto> = emptyList(),
    val outbox: List<SosOutboxItem> = emptyList(),
    val selected: SosEventDto? = null,
    val recipients: List<SosRecipientDto> = emptyList(),
    val loading: Boolean = false,
    val pending: Boolean = false,
    val offline: Boolean = false,
    val pushRegistered: Boolean? = null,
    val pushConfigured: Boolean? = null,
    val error: String? = null,
)

class SosViewModel(
    private val repository: SosDataSource,
    private val outbox: SosOutbox,
    private val engine: SosSyncEngine,
    private val scheduler: SosSyncScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutable = MutableStateFlow(SosState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var loadJob: Job? = null
    private var mutationJob: Job? = null

    fun activate(owner: String?) {
        generation++
        loadJob?.cancel(); mutationJob?.cancel()
        mutable.value = SosState(owner = owner)
        if (owner != null) refresh()
    }

    fun refresh(keepError: Boolean = false) {
        val owner = mutable.value.owner ?: return
        if (mutable.value.pending) return
        val priorError = if (keepError) mutable.value.error else null
        val selectedId = mutable.value.selected?.id
        val token = ++generation
        loadJob?.cancel()
        mutable.update { it.copy(loading = true, error = priorError) }
        loadJob = viewModelScope.launch {
            try {
                var local = outbox.read(owner)
                val cached = outbox.cachedMembers(owner).filter { now() - it.savedAtMillis < CACHE_AGE_MILLIS }
                var offline = false
                val members = try {
                    repository.members(owner).map { SosCachedMember(owner, it.userId, it.displayName, now()) }.also {
                        if (generation == token && mutable.value.owner == owner) outbox.saveMembers(owner, it)
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { offline = true; cached }
                val events = try { repository.events(owner) } catch (e: CancellationException) { throw e } catch (_: Exception) { offline = true; emptyList() }
                val pushRegistered = try { repository.pushRegistered(owner) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                val pushConfigured = try { repository.pushConfigured(owner) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (generation == token && mutable.value.owner == owner) {
                    local.filter { it.state == SosLocalState.CONFIRMED || it.state == SosLocalState.UNKNOWN }.forEach { item ->
                        val serverState = events.firstOrNull { it.id == item.eventId && it.ownerId == owner }?.state
                        val reconciled = when (serverState) {
                            "ACTIVE" -> SosLocalState.CONFIRMED
                            "CANCELLED" -> SosLocalState.CANCELLED
                            "RESOLVED" -> SosLocalState.RESOLVED
                            "EXPIRED" -> SosLocalState.SERVER_EXPIRED
                            else -> null
                        }
                        if (reconciled != null && reconciled != item.state) outbox.replace(owner, item.eventId, item.state, reconciled)
                    }
                    local = outbox.read(owner)
                }
                val selected = selectedId?.let { id -> events.firstOrNull { it.id == id } }
                val recipients = if (selected?.ownerId == owner) repository.recipients(owner, selected.id) else emptyList()
                if (generation != token || mutable.value.owner != owner) return@launch
                mutable.update { it.copy(members = members, events = events, outbox = local, selected = selected,
                    recipients = recipients, loading = false, offline = offline, pushRegistered = pushRegistered,
                    pushConfigured = pushConfigured, error = priorError) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (generation == token && mutable.value.owner == owner) mutable.update {
                    it.copy(members = emptyList(), events = emptyList(), outbox = emptyList(), selected = null,
                        recipients = emptyList(), loading = false, pushRegistered = null, pushConfigured = null,
                        error = "Không thể đọc dữ liệu SOS. Hãy kiểm tra kết nối và bộ nhớ thiết bị.")
                }
            }
        }
    }

    fun send(recipientIds: Set<String>) {
        val owner = mutable.value.owner ?: return
        if (mutable.value.pending || recipientIds.isEmpty() || recipientIds.any { id -> mutable.value.members.none { it.userId == id } }) return
        mutate {
            val at = now()
            try {
                outbox.enqueue(SosOutboxItem(UUID.randomUUID().toString(), owner, Instant.ofEpochMilli(at).toString(), recipientIds.sorted(), at, SosLocalState.QUEUED))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { throw SosLocalQueueException(e) }
            scheduler.schedule(owner)
        }
    }

    fun select(id: String) {
        val owner = mutable.value.owner ?: return
        val token = ++generation
        loadJob?.cancel()
        mutable.update { it.copy(selected = null, recipients = emptyList(), loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val event = repository.event(owner, id)
                val recipients = if (event.ownerId == owner) repository.recipients(owner, id) else emptyList()
                val members = try {
                    repository.members(owner).map { SosCachedMember(owner, it.userId, it.displayName, now()) }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.value.members }
                if (generation == token && mutable.value.owner == owner) mutable.update { it.copy(selected = event, recipients = recipients, members = members, loading = false) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (generation == token && mutable.value.owner == owner) mutable.update { it.copy(selected = null, recipients = emptyList(), loading = false, error = "Không thể kiểm tra sự kiện hoặc quyền hiện tại.") }
            }
        }
    }

    fun cancel(id: String) = mutate { owner ->
        val item = outbox.read(owner).firstOrNull { it.eventId == id }
        if (item == null) repository.cancel(owner, id) else { engine.requestCancel(owner, id); scheduler.schedule(owner) }
    }
    fun acknowledge(id: String) = mutate { owner -> repository.acknowledge(owner, id) }
    fun recheckCancel(id: String) = mutate { owner ->
        repository.cancel(owner, id)
        outbox.replace(owner, id, SosLocalState.CANCEL_REQUESTED, SosLocalState.CANCELLED)
    }
    fun recheckUnknown(id: String) = mutate { owner ->
        val event = repository.event(owner, id)
        check(event.ownerId == owner && event.id == id)
        val state = when (event.state) {
            "ACTIVE" -> SosLocalState.CONFIRMED
            "CANCELLED" -> SosLocalState.CANCELLED
            "RESOLVED" -> SosLocalState.RESOLVED
            "EXPIRED" -> SosLocalState.SERVER_EXPIRED
            else -> error("unrecognized SOS state")
        }
        outbox.replace(owner, id, SosLocalState.UNKNOWN, state)
    }
    fun revokeRecipient(id: String, recipient: String) = mutate { owner -> repository.revokeRecipient(owner, id, recipient) }
    fun resolve(id: String) = mutate { owner ->
        repository.resolve(owner, id)
        outbox.replace(owner, id, SosLocalState.CONFIRMED, SosLocalState.RESOLVED)
    }
    fun clearFinished(id: String) = mutate { owner -> outbox.removeFinished(owner, id) }

    private fun mutate(action: suspend (String) -> Unit) {
        val owner = mutable.value.owner ?: return
        if (mutable.value.pending) return
        val token = ++generation
        loadJob?.cancel()
        mutable.update { it.copy(pending = true, selected = null, recipients = emptyList(), error = null) }
        mutationJob = viewModelScope.launch {
            try { action(owner) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (generation == token && mutable.value.owner == owner) mutable.update {
                    it.copy(error = if (e is SosLocalQueueException) "Không xác nhận được việc lưu SOS. Hãy liên hệ trực tiếp và kiểm tra bộ nhớ thiết bị." else "Chưa xác nhận được thao tác. Hãy kiểm tra trạng thái trước khi thử lại.")
                }
            } finally {
                if (generation == token && mutable.value.owner == owner) {
                    mutable.update { it.copy(pending = false) }
                    refresh(keepError = true)
                }
            }
        }
    }

    companion object {
        const val CACHE_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SosViewModel(container.sosRepository, container.sosOutbox, container.sosSyncEngine, container.sosSyncScheduler) as T
        }
    }
}
