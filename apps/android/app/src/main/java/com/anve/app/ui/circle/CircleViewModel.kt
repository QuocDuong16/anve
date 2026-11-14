package com.anve.app.ui.circle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anve.app.R
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
    /** True once the member list of the current circle has been fetched (or its fetch failed);
     *  the activation checklist waits for it so its progress reflects the actual membership. */
    val membersLoaded: Boolean = false,
    val busy: Boolean = false,
    val messageRes: Int? = null,
    val errorRes: Int? = null,
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
                    // Keep the previously loaded members of the same circle while
                    // refreshing, so the activation checklist does not flash back
                    // on between the circle snapshot and the member list arriving.
                    val sameCircle = it.circle?.id == mine.circle.id
                    it.copy(
                        loading = false,
                        circle = mine.circle,
                        myRole = mine.membership.role,
                        members = if (sameCircle) it.members else emptyList(),
                        invites = if (sameCircle) it.invites else emptyList(),
                        membersLoaded = sameCircle && it.membersLoaded,
                    )
                }
                loadDetails(mine.circle.id, expected)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (expected == revision) _state.update { it.copy(loading = false, errorRes = R.string.circle_load_failed) }
            }
        }
    }

    fun acceptJoined(owner: String, joined: CircleMembershipDto) {
        val circle = joined.circle ?: return
        val membership = joined.membership ?: return
        if (membership.userId != owner || membership.circleId != circle.id) return
        val expected = ++revision
        _state.value = CircleUiState(loading = false, circle = circle, myRole = membership.role, messageRes = R.string.circle_joined)
        loadDetails(circle.id, expected)
    }

    private fun loadDetails(circleId: String, expected: Long = revision) {
        scope.launch {
            try {
                val members = repository.members(circleId)
                if (expected != revision || _state.value.circle?.id != circleId) return@launch
                _state.update { it.copy(members = members, membersLoaded = true) }
                if (_state.value.isOwner) {
                    // Non-owners get FORBIDDEN here; only owners ask.
                    runCatching { repository.invites(circleId) }.onSuccess { invites ->
                        if (expected == revision && _state.value.circle?.id == circleId) _state.update { it.copy(invites = invites) }
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                // Members are the core content; invites are owner extras.
                if (expected == revision && _state.value.circle?.id == circleId) {
                    _state.update { it.copy(errorRes = R.string.circle_members_failed, membersLoaded = true) }
                }
            }
        }
    }

    fun create(name: String) {
        if (_state.value.busy || name.isBlank()) return
        scope.launch {
            setBusy(messageRes = null, errorRes = null)
            try {
                repository.create(name.trim())
                _state.update { CircleUiState(loading = false, messageRes = R.string.circle_created) }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, errorRes = circleError(e, R.string.circle_error_create_failed)) }
            }
        }
    }

    fun join(code: String) {
        if (_state.value.busy || code.isBlank()) return
        scope.launch {
            setBusy(messageRes = null, errorRes = null)
            try {
                repository.joinWithCode(code.trim().uppercase())
                _state.update { CircleUiState(loading = false, messageRes = R.string.circle_joined) }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, errorRes = circleError(e, R.string.circle_error_join_failed)) }
            }
        }
    }

    fun createInvite() {
        val circle = _state.value.circle ?: return
        scope.launch {
            setBusy(messageRes = null, errorRes = null)
            try {
                repository.createInvite(circle.id)
                loadDetails(circle.id)
                _state.update { it.copy(busy = false, messageRes = R.string.circle_invite_created) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, errorRes = circleError(e, R.string.circle_error_invite_create_failed)) }
            }
        }
    }

    fun revokeInvite(inviteId: String) {
        val circle = _state.value.circle ?: return
        scope.launch {
            setBusy(messageRes = null, errorRes = null)
            try {
                repository.revokeInvite(inviteId)
                loadDetails(circle.id)
                _state.update { it.copy(busy = false, messageRes = R.string.circle_invite_revoked) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, errorRes = R.string.circle_invite_revoke_failed) }
            }
        }
    }

    fun removeMember(userId: String) {
        val circle = _state.value.circle ?: return
        scope.launch {
            try {
                repository.removeMember(circle.id, userId)
                loadDetails(circle.id)
                _state.update { it.copy(messageRes = R.string.circle_member_removed) }
            } catch (e: Exception) {
                _state.update { it.copy(errorRes = circleError(e, R.string.circle_error_remove_member_failed)) }
            }
        }
    }

    fun leave(dissolve: Boolean) {
        val circle = _state.value.circle ?: return
        scope.launch {
            setBusy(messageRes = null, errorRes = null)
            try {
                repository.leave(circle.id, dissolve)
                _state.update { CircleUiState(loading = false, messageRes = R.string.circle_left) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, errorRes = circleError(e, R.string.circle_error_leave_failed)) }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(messageRes = null, errorRes = null) }
    }

    private fun setBusy(messageRes: Int?, errorRes: Int?) {
        _state.update { it.copy(busy = true, messageRes = messageRes, errorRes = errorRes) }
    }

    /**
     * Maps the contract's 409 codes to their copy; other failures get the
     * caller's action-specific sentence.
     */
    private fun circleError(e: Exception, actionFailedRes: Int): Int = when {
        e is ApiException && e.code == "ACTIVE_CIRCLE_EXISTS" -> R.string.circle_error_active_circle
        e is ApiException && e.code == "INVITE_UNAVAILABLE" -> R.string.circle_error_invite_unavailable
        e is ApiException && e.code == "CIRCLE_HAS_MEMBERS" -> R.string.circle_error_circle_has_members
        e is ApiException && e.code == "FORBIDDEN" -> R.string.circle_error_forbidden
        else -> actionFailedRes
    }

    companion object {
        fun factory(repository: CircleRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CircleViewModel(repository) as T
            }
    }
}
