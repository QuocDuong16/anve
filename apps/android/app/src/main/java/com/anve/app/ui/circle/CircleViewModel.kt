package com.anve.app.ui.circle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.CircleDto
import com.anve.app.data.api.CircleMembershipDto
import com.anve.app.data.api.InviteDto
import com.anve.app.data.api.MemberDto
import com.anve.app.data.circles.CircleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the circle tab renders. */
data class CircleUiState(
    val loading: Boolean = true,
    val circle: CircleDto? = null,
    val myRole: String? = null,
    val members: List<MemberDto> = emptyList(),
    val invites: List<InviteDto> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val isOwner: Boolean get() = myRole == "owner"
}

class CircleViewModel internal constructor(
    private val repository: CircleRepository,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    /** Production uses `viewModelScope`; tests inject a disposable scope. */
    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val _state = MutableStateFlow(CircleUiState())
    val state: StateFlow<CircleUiState> = _state.asStateFlow()
    private var revision = 0L

    init {
        refresh()
    }

    fun refresh() {
        val expected = ++revision
        scope.launch {
            try {
                val mine = repository.myCircle()
                if (expected != revision) return@launch
                if (mine.circle == null || mine.membership == null) {
                    _state.update { CircleUiState(loading = false) }
                    return@launch
                }
                _state.update {
                    it.copy(
                        loading = false,
                        circle = mine.circle,
                        myRole = mine.membership.role,
                        members = emptyList(),
                        invites = emptyList(),
                    )
                }
                loadDetails(mine.circle.id, expected)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (expected == revision) _state.update { it.copy(loading = false, error = "Không tải được thông tin nhóm.") }
            }
        }
    }

    fun acceptJoined(owner: String, joined: CircleMembershipDto) {
        val circle = joined.circle ?: return
        val membership = joined.membership ?: return
        if (membership.userId != owner || membership.circleId != circle.id) return
        val expected = ++revision
        _state.value = CircleUiState(loading = false, circle = circle, myRole = membership.role, message = "Đã tham gia nhóm.")
        loadDetails(circle.id, expected)
    }

    private fun loadDetails(circleId: String, expected: Long = revision) {
        scope.launch {
            try {
                val members = repository.members(circleId)
                if (expected != revision || _state.value.circle?.id != circleId) return@launch
                _state.update { it.copy(members = members) }
                if (_state.value.isOwner) {
                    // Non-owners get FORBIDDEN here; only owners ask.
                    runCatching { repository.invites(circleId) }.onSuccess { invites ->
                        if (expected == revision && _state.value.circle?.id == circleId) _state.update { it.copy(invites = invites) }
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                // Members are the core content; invites are owner extras.
                if (expected == revision && _state.value.circle?.id == circleId) _state.update { it.copy(error = "Không tải được danh sách thành viên.") }
            }
        }
    }

    fun create(name: String) {
        if (_state.value.busy || name.isBlank()) return
        scope.launch {
            setBusy(message = null, error = null)
            try {
                repository.create(name.trim())
                _state.update { CircleUiState(loading = false, message = "Đã tạo nhóm gia đình.") }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = circleError(e, "tạo nhóm")) }
            }
        }
    }

    fun join(code: String) {
        if (_state.value.busy || code.isBlank()) return
        scope.launch {
            setBusy(message = null, error = null)
            try {
                repository.joinWithCode(code.trim().uppercase())
                _state.update { CircleUiState(loading = false, message = "Đã tham gia nhóm.") }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = circleError(e, "tham gia nhóm")) }
            }
        }
    }

    fun createInvite() {
        val circle = _state.value.circle ?: return
        scope.launch {
            setBusy(message = null, error = null)
            try {
                repository.createInvite(circle.id)
                loadDetails(circle.id)
                _state.update { it.copy(busy = false, message = "Đã tạo mã mời mới.") }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = circleError(e, "tạo mã mời")) }
            }
        }
    }

    fun revokeInvite(inviteId: String) {
        val circle = _state.value.circle ?: return
        scope.launch {
            try {
                repository.revokeInvite(inviteId)
                loadDetails(circle.id)
            } catch (e: Exception) {
                _state.update { it.copy(error = "Không thu hồi được mã mời.") }
            }
        }
    }

    fun removeMember(userId: String) {
        val circle = _state.value.circle ?: return
        scope.launch {
            try {
                repository.removeMember(circle.id, userId)
                loadDetails(circle.id)
                _state.update { it.copy(message = "Đã xóa thành viên khỏi nhóm.") }
            } catch (e: Exception) {
                _state.update { it.copy(error = circleError(e, "xóa thành viên")) }
            }
        }
    }

    fun leave(dissolve: Boolean) {
        val circle = _state.value.circle ?: return
        scope.launch {
            setBusy(message = null, error = null)
            try {
                repository.leave(circle.id, dissolve)
                _state.update { CircleUiState(loading = false, message = "Bạn đã rời nhóm.") }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = circleError(e, "rời nhóm")) }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null, error = null) }
    }

    private fun setBusy(message: String?, error: String?) {
        _state.update { it.copy(busy = true, message = message, error = error) }
    }

    companion object {
        fun circleError(e: Exception, action: String): String = when {
            e is ApiException && e.code == "ACTIVE_CIRCLE_EXISTS" ->
                "Bạn đã thuộc một nhóm. Hãy rời nhóm hiện tại trước."
            e is ApiException && e.code == "INVITE_UNAVAILABLE" ->
                "Mã mời không tồn tại, đã hết hạn hoặc đã được dùng."
            e is ApiException && e.code == "CIRCLE_HAS_MEMBERS" ->
                "Nhóm vẫn còn thành viên. Chọn giải tán nhóm nếu bạn chắc chắn."
            e is ApiException && e.code == "FORBIDDEN" -> "Bạn không có quyền thực hiện việc này."
            else -> "Không $action được. Vui lòng thử lại."
        }

        fun factory(repository: CircleRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CircleViewModel(repository) as T
            }
    }
}
