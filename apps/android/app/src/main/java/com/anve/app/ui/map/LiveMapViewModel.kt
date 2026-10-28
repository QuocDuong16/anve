package com.anve.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.data.location.AudienceMember
import com.anve.app.data.location.SharingManagementSnapshot
import com.anve.app.data.location.VisibleLocationRepository
import com.anve.app.data.location.VisibleLocationSnapshot
import kotlinx.coroutines.CancellationException
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
    val sharingError: String? = null,
)

class LiveMapViewModel internal constructor(
    private val repository: VisibleLocationRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LiveMapUiState())
    val state: StateFlow<LiveMapUiState> = _state.asStateFlow()

    fun ownerChanged(ownerId: String?) {
        if (_state.value.ownerId == ownerId) return
        _state.value = LiveMapUiState(ownerId = ownerId)
        if (ownerId != null) loadSharing(ownerId)
    }

    fun applySnapshot(snapshot: VisibleLocationSnapshot) {
        if (snapshot.ownerId != _state.value.ownerId) return
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
        if (_state.value.pendingViewerIds.contains(viewer.userId)) return
        val sharing = _state.value.sharing ?: return
        if (!sharing.consentEnabled || !sharing.hasCircle) return
        _state.update {
            it.copy(
                pendingViewerIds = it.pendingViewerIds + viewer.userId,
                sharingError = null,
            )
        }
        viewModelScope.launch {
            try {
                repository.setViewerGrant(ownerId, viewer.userId, enabled)
                if (_state.value.ownerId == ownerId) loadSharing(ownerId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update {
                    if (it.ownerId != ownerId) it else {
                        it.copy(sharingError = "Không cập nhật được quyền xem. Trạng thái máy chủ chưa đổi.")
                    }
                }
                if (_state.value.ownerId == ownerId) loadSharing(ownerId)
            } finally {
                _state.update {
                    if (it.ownerId != ownerId) it else {
                        it.copy(pendingViewerIds = it.pendingViewerIds - viewer.userId)
                    }
                }
            }
        }
    }

    private fun loadSharing(ownerId: String) {
        _state.update { it.copy(sharingLoading = true, sharingError = null) }
        viewModelScope.launch {
            try {
                val snapshot = repository.sharingManagement()
                if (_state.value.ownerId == ownerId) {
                    _state.update { it.copy(sharing = snapshot, sharingLoading = false) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (_state.value.ownerId == ownerId) {
                    _state.update {
                        it.copy(
                            sharingLoading = false,
                            sharingError = "Không tải được danh sách quyền xem. Thử lại khi có kết nối.",
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun factory(repository: VisibleLocationRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(LiveMapViewModel::class.java))
                    return LiveMapViewModel(repository) as T
                }
            }
    }
}
