package com.anve.app.ui.circle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.CircleMembershipDto
import com.anve.app.data.api.InvitePreviewDto
import com.anve.app.data.auth.AuthRepository
import com.anve.app.data.auth.SessionStorage
import com.anve.app.data.circles.CircleRepository
import com.anve.app.data.circles.InvitationConfirmation
import com.anve.app.data.circles.InvitationInbox
import com.anve.app.data.circles.confirmInvitation
import com.anve.app.data.circles.reconcileInvitation
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InvitationUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val preview: InvitePreviewDto? = null,
    val accountLabel: String = "",
    val blocked: Boolean = false,
    val error: String? = null,
    val retryable: Boolean = false,
    val unknown: Boolean = false,
    val joined: Boolean = false,
    val joinedMembership: CircleMembershipDto? = null,
)

interface InvitationGateway {
    suspend fun accountLabel(owner: String): String
    suspend fun preview(owner: String, code: String): InvitePreviewDto
    suspend fun mine(owner: String): CircleMembershipDto
    suspend fun confirm(owner: String, code: String, circle: String): InvitationConfirmation
    suspend fun reconcile(owner: String, circle: String): InvitationConfirmation
}

class RepositoryInvitationGateway(private val circles: CircleRepository, private val auth: AuthRepository) : InvitationGateway {
    override suspend fun accountLabel(owner: String): String {
        val user = auth.me()
        check(user.id == owner)
        return "${user.displayName} · ${user.email}"
    }
    override suspend fun preview(owner: String, code: String) = circles.previewInvite(owner, code)
    override suspend fun mine(owner: String) = circles.myCircle(owner)
    override suspend fun confirm(owner: String, code: String, circle: String) = circles.confirmInvitation(owner, code, circle)
    override suspend fun reconcile(owner: String, circle: String) = circles.reconcileInvitation(owner, circle)
}

/** Every admission and response belongs to one account and one memory-only link. */
class InvitationViewModel(
    private val owner: String,
    private val invitationId: String,
    private val inbox: InvitationInbox,
    private val sessions: SessionStorage,
    private val gateway: InvitationGateway,
) : ViewModel() {
    private val _state = MutableStateFlow(InvitationUiState())
    val state = _state.asStateFlow()
    private var active = true
    private var sequence = 0L
    private var work: Job? = null

    init {
        viewModelScope.launch { sessions.session.collect { if (it?.userId != owner) deactivate() } }
        load()
    }

    private suspend fun current(): Boolean = try {
        active && sessions.current()?.userId == owner && inbox.current(invitationId, owner) != null
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        _state.value = InvitationUiState(loading = false, blocked = true, error = "Chưa kiểm tra được tài khoản. Đóng lời mời và mở lại khi phiên đăng nhập sẵn sàng.")
        false
    }

    fun deactivate() {
        active = false
        sequence++
        work?.cancel()
        _state.value = InvitationUiState(loading = false)
    }

    fun load() {
        if (!active || _state.value.busy || _state.value.unknown) return
        val revision = ++sequence
        work?.cancel()
        _state.value = InvitationUiState()
        work = viewModelScope.launch {
            try {
                if (!current()) return@launch
                val code = inbox.current(invitationId, owner)?.code
                if (code == null) {
                    _state.value = InvitationUiState(loading = false, error = "Link mời không hợp lệ. Nhờ người thân gửi lại link hoặc nhập mã mời.")
                    return@launch
                }
                val label = gateway.accountLabel(owner)
                val preview = gateway.preview(owner, code)
                val mine = gateway.mine(owner)
                if (!current() || revision != sequence) return@launch
                val expired = runCatching { !Instant.parse(preview.expiresAt).isAfter(Instant.now()) }.getOrDefault(true)
                val inCircle = mine.circle != null || mine.membership != null
                _state.value = InvitationUiState(loading = false, preview = preview, accountLabel = label, blocked = inCircle || expired,
                    error = when { expired -> "Lời mời đã hết hạn. Nhờ người thân tạo lời mời mới."; mine.circle?.id == preview.circleId -> "Bạn đã là thành viên của nhóm này."; inCircle -> "Bạn đang ở trong một nhóm gia đình. Hãy quản lý nhóm hiện tại trước khi tham gia nhóm khác."; else -> null })
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (current() && revision == sequence) {
                    val unavailable = e is ApiException && e.code == "INVITE_UNAVAILABLE"
                    _state.value = InvitationUiState(loading = false, error = if (unavailable) "Lời mời không còn dùng được: đã hết hạn, được dùng hoặc bị thu hồi. Nhờ người thân gửi lời mời mới."
                        else "Chưa tải được lời mời. Kiểm tra mạng rồi thử lại.", retryable = !unavailable)
                }
            }
        }
    }

    fun confirm() {
        val before = _state.value
        val preview = before.preview ?: return
        if (!active || before.loading || before.busy || before.blocked || before.unknown || before.joined) return
        if (runCatching { !Instant.parse(preview.expiresAt).isAfter(Instant.now()) }.getOrDefault(true)) {
            _state.update { it.copy(blocked = true, error = "Lời mời đã hết hạn. Nhờ người thân tạo lời mời mới.") }
            return
        }
        submit(checkOnly = false)
    }

    fun checkAgain() { if (_state.value.unknown && !_state.value.busy) submit(checkOnly = true) }

    private fun submit(checkOnly: Boolean) {
        val preview = _state.value.preview ?: return
        val revision = ++sequence
        _state.update { it.copy(busy = true, error = null) }
        work = viewModelScope.launch {
            try {
                if (!current()) return@launch
                val code = inbox.current(invitationId, owner)?.code ?: return@launch
                val result = if (checkOnly) gateway.reconcile(owner, preview.circleId) else gateway.confirm(owner, code, preview.circleId)
                if (!current() || revision != sequence) return@launch
                when (result) {
                    is InvitationConfirmation.Joined -> _state.update { it.copy(busy = false, joined = true, joinedMembership = result.membership, unknown = false) }
                    is InvitationConfirmation.Rejected -> _state.update { it.copy(busy = false, blocked = true, unknown = false,
                        error = if (result.code == "ACTIVE_CIRCLE_EXISTS") "Bạn đã thuộc một nhóm gia đình. Kiểm tra nhóm hiện tại trước."
                        else "Máy chủ chưa cho phép tham gia. Lời mời có thể đã hết hạn, được dùng hoặc bị thu hồi.") }
                    InvitationConfirmation.Unknown -> uncertain()
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (current() && revision == sequence) uncertain()
            }
        }
    }

    private fun uncertain() {
        _state.update { it.copy(busy = false, unknown = true, error = "Máy chủ chưa xác nhận tham gia. Kiểm tra kết quả; ứng dụng không tự gửi lại yêu cầu.") }
    }
}
