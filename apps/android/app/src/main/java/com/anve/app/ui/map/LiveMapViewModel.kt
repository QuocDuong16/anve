package com.anve.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.data.location.AudienceMember
import com.anve.app.data.location.AudienceMutationOutcome
import com.anve.app.data.location.SharingManagementSnapshot
import com.anve.app.data.location.SharingManagementDataSource
import com.anve.app.data.location.VisibleLocationSnapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiveMapUiState(
    val ownerId: String? = null,
    val snapshot: VisibleLocationSnapshot? = null,
    val sharing: SharingManagementSnapshot? = null,
    val sharingLoading: Boolean = false,
    val sharingOpen: Boolean = false,
    val pendingViewerIds: Set<String> = emptySet(),
    val unresolvedViewerIds: Set<String> = emptySet(),
    val sharingError: String? = null,
)

class LiveMapViewModel internal constructor(
    private val repository: SharingManagementDataSource,
) : ViewModel() {
    private val _state = MutableStateFlow(LiveMapUiState())
    val state: StateFlow<LiveMapUiState> = _state.asStateFlow()
    private val mutationLock = Any()
    private val activeMutations = mutableSetOf<MutationKey>()
    private val mutationJobs = mutableMapOf<MutationKey, Job>()
    private val reconciliationWaiters = mutableSetOf<String>()
    private val unresolvedDesired = mutableMapOf<String, Boolean>()
    private val outcomeMessages = mutableMapOf<String, String>()
    private var ownerGeneration = 0L
    private var sharingGeneration = 0L
    private var locationSnapshotGeneration = 0L
    private var sharingLoadJob: Job? = null

    fun ownerChanged(ownerId: String?) {
        if (_state.value.ownerId == ownerId) return
        ownerGeneration++
        sharingGeneration++
        locationSnapshotGeneration++
        sharingLoadJob?.cancel()
        sharingLoadJob = null
        val oldJobs = synchronized(mutationLock) {
            val jobs = mutationJobs.values.toList()
            mutationJobs.clear()
            activeMutations.clear()
            jobs
        }
        oldJobs.forEach(Job::cancel)
        reconciliationWaiters.clear()
        unresolvedDesired.clear()
        outcomeMessages.clear()
        _state.value = LiveMapUiState(ownerId = ownerId)
        if (ownerId != null) loadSharing(ownerId)
    }

    fun beginLocationSnapshotCollection(expectedOwnerId: String): Long {
        if (_state.value.ownerId != expectedOwnerId) return -1L
        return ++locationSnapshotGeneration
    }

    fun applySnapshot(snapshot: VisibleLocationSnapshot, collectionGeneration: Long) {
        if (snapshot.ownerId != _state.value.ownerId || collectionGeneration != locationSnapshotGeneration) return
        _state.update { it.copy(snapshot = snapshot) }
    }

    fun showSharing(open: Boolean, expectedOwnerId: String?) {
        if (_state.value.ownerId != expectedOwnerId) {
            ownerChanged(expectedOwnerId)
            return
        }
        _state.update { it.copy(sharingOpen = open, sharingError = null) }
        if (open) _state.value.ownerId?.let(::loadSharing)
    }

    fun refreshSharing(expectedOwnerId: String?) {
        if (_state.value.ownerId != expectedOwnerId) {
            ownerChanged(expectedOwnerId)
            return
        }
        _state.value.ownerId?.let(::loadSharing)
    }

    fun toggleViewer(expectedOwnerId: String?, viewer: AudienceMember, enabled: Boolean) {
        if (expectedOwnerId == null || _state.value.ownerId != expectedOwnerId) {
            ownerChanged(expectedOwnerId)
            return
        }
        val ownerId = _state.value.ownerId ?: return
        if (_state.value.unresolvedViewerIds.contains(viewer.userId)) return
        val sharing = _state.value.sharing ?: return
        if (!sharing.consentEnabled || !sharing.hasCircle) return
        val key = MutationKey(ownerId, viewer.userId)
        val ownerToken = ownerGeneration
        synchronized(mutationLock) {
            if (!activeMutations.add(key)) return
        }
        sharingGeneration++
        sharingLoadJob?.cancel()
        sharingLoadJob = null
        _state.update {
            it.copy(
                pendingViewerIds = it.pendingViewerIds + viewer.userId,
                unresolvedViewerIds = it.unresolvedViewerIds - viewer.userId,
                sharingError = null,
            )
        }
        unresolvedDesired.remove(viewer.userId)
        outcomeMessages.remove(viewer.userId)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val outcome = try {
                    repository.setViewerGrant(ownerId, viewer.userId, enabled)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AudienceMutationOutcome.UnknownOutcome(
                        snapshot = null,
                        requestedEnabled = enabled,
                        currentEnabled = null,
                    )
                }
                if (!isOwnerCurrent(ownerId, ownerToken)) return@launch
                when (outcome) {
                    is AudienceMutationOutcome.Confirmed -> {
                        unresolvedDesired.remove(viewer.userId)
                        outcomeMessages.remove(viewer.userId)
                    }
                    is AudienceMutationOutcome.Rejected -> {
                        unresolvedDesired.remove(viewer.userId)
                        outcomeMessages[viewer.userId] = "Máy chủ từ chối thay đổi quyền xem."
                    }
                    is AudienceMutationOutcome.UnknownOutcome -> {
                        unresolvedDesired[viewer.userId] = outcome.requestedEnabled
                        outcomeMessages[viewer.userId] = unknownOutcomeMessage(
                            outcome.requestedEnabled,
                            outcome.currentEnabled,
                        )
                    }
                }
                _state.update {
                    if (it.ownerId != ownerId) {
                        it
                    } else {
                        it.copy(
                            unresolvedViewerIds = if (outcome is AudienceMutationOutcome.UnknownOutcome) {
                                it.unresolvedViewerIds + viewer.userId
                            } else {
                                it.unresolvedViewerIds - viewer.userId
                            },
                            sharingError = outcomeMessages[viewer.userId],
                        )
                    }
                }
                reconciliationWaiters += viewer.userId
            } catch (error: CancellationException) {
                if (isOwnerCurrent(ownerId, ownerToken)) {
                    unresolvedDesired[viewer.userId] = enabled
                    outcomeMessages[viewer.userId] = unknownOutcomeMessage(enabled, null)
                    reconciliationWaiters += viewer.userId
                    _state.update {
                        if (it.ownerId != ownerId) it else {
                            it.copy(
                                unresolvedViewerIds = it.unresolvedViewerIds + viewer.userId,
                                sharingError = outcomeMessages[viewer.userId],
                            )
                        }
                    }
                }
                throw error
            } finally {
                synchronized(mutationLock) {
                    activeMutations.remove(key)
                    mutationJobs.remove(key)
                }
                if (isOwnerCurrent(ownerId, ownerToken)) loadSharing(ownerId)
            }
        }
        synchronized(mutationLock) { mutationJobs[key] = job }
        job.start()
    }

    private fun loadSharing(ownerId: String) {
        val ownerToken = ownerGeneration
        val requestToken = ++sharingGeneration
        sharingLoadJob?.cancel()
        _state.update { it.copy(sharingLoading = true, sharingError = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val snapshot = repository.sharingManagement(ownerId)
                if (isRequestCurrent(ownerId, ownerToken, requestToken)) {
                    val readyToRelease = reconciliationWaiters.toSet()
                    val unresolved = unresolvedDesired.toMap()
                    val stillUnresolved = unresolved.filter { (viewerId, desired) ->
                        val actual = snapshot.viewerAccess(viewerId)
                        actual != desired
                    }
                    val resolvedViewerIds = unresolved.keys - stillUnresolved.keys
                    unresolvedDesired.keys.removeAll(resolvedViewerIds)
                    outcomeMessages.keys.removeAll(resolvedViewerIds)
                    reconciliationWaiters.removeAll(readyToRelease)
                    val activeViewerIds = synchronized(mutationLock) {
                        activeMutations.filter { it.ownerId == ownerId }.mapTo(mutableSetOf()) { it.viewerId }
                    }
                    _state.update {
                        it.copy(
                            sharing = snapshot,
                            sharingLoading = false,
                            pendingViewerIds = it.pendingViewerIds - (readyToRelease - activeViewerIds),
                            unresolvedViewerIds = stillUnresolved.keys,
                            sharingError = sharingOutcomeMessage(stillUnresolved, outcomeMessages, snapshot),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (isRequestCurrent(ownerId, ownerToken, requestToken)) {
                    _state.update {
                        it.copy(
                            sharingLoading = false,
                            sharingError = sharingLoadFailureMessage(it.pendingViewerIds),
                        )
                    }
                }
            }
        }
        sharingLoadJob = job
        job.start()
    }

    private fun isOwnerCurrent(ownerId: String, ownerToken: Long): Boolean =
        _state.value.ownerId == ownerId && ownerGeneration == ownerToken

    private fun isRequestCurrent(ownerId: String, ownerToken: Long, requestToken: Long): Boolean =
        isOwnerCurrent(ownerId, ownerToken) && sharingGeneration == requestToken

    private data class MutationKey(val ownerId: String, val viewerId: String)

    private fun SharingManagementSnapshot.viewerAccess(viewerId: String): Boolean? =
        members.firstOrNull { it.userId == viewerId }?.canViewLocation
            ?: if (members.none { it.userId == viewerId }) false else null

    private fun unknownOutcomeMessage(desired: Boolean, current: Boolean?): String = when {
        current == true && !desired ->
            "Chưa xác nhận việc thu hồi; máy chủ hiện vẫn cho biết người này có quyền xem."
        current == false && desired ->
            "Chưa xác nhận việc cấp quyền; máy chủ hiện chưa hiển thị quyền này."
        else -> "Chưa xác định kết quả thay đổi quyền xem. Đang giữ trạng thái chờ đối chiếu."
    }

    private fun sharingOutcomeMessage(
        unresolved: Map<String, Boolean>,
        messages: Map<String, String>,
        snapshot: SharingManagementSnapshot,
    ): String? {
        val unresolvedMessage = unresolved.entries.firstNotNullOfOrNull { (viewerId, desired) ->
            val actual = snapshot.viewerAccess(viewerId)
            if (actual == desired) null else unknownOutcomeMessage(desired, actual)
        }
        return unresolvedMessage ?: messages.values.firstOrNull()
    }

    private fun sharingLoadFailureMessage(pendingViewerIds: Set<String>): String = when {
        pendingViewerIds.any { it in unresolvedDesired } ->
            "Chưa xác định kết quả thay đổi quyền xem. Không tải được trạng thái máy chủ; quyền vẫn đang chờ đối chiếu."
        pendingViewerIds.isNotEmpty() ->
            "Chưa tải được danh sách quyền hiện tại. Thay đổi vẫn đang chờ đối chiếu với máy chủ."
        else -> "Không tải được danh sách quyền xem. Thử lại khi có kết nối."
    }

    companion object {
        fun factory(repository: SharingManagementDataSource): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(LiveMapViewModel::class.java))
                    return LiveMapViewModel(repository) as T
                }
            }
    }
}
