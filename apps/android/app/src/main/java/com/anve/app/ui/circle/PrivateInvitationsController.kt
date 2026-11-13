package com.anve.app.ui.circle

import com.anve.app.data.api.ApiException
import com.anve.app.data.api.InviteDto
import com.anve.app.data.api.PrivateInvitationRequestDto
import com.anve.app.data.api.ReceivedInvitationDto
import com.anve.app.data.auth.SessionStorage
import com.anve.app.data.circles.InviteLink
import com.anve.app.data.circles.PrivateInvitationGateway
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PrivateInvitationsState(
    val open: Boolean = false, val mode: String = "EMAIL", val email: String = "", val hours: Int = 48,
    val busy: Boolean = false, val unknown: Boolean = false, val error: String? = null, val message: String? = null,
    val received: List<ReceivedInvitationDto> = emptyList(), val loading: Boolean = true,
    val inboxError: String? = null, val detailBusy: Boolean = false,
) {
    override fun toString() = "PrivateInvitationsState(open=$open, busy=$busy, unknown=$unknown)"
}

/** Drafts and capabilities are screen-local; only the creation UUID survives a
 * closed sheet until the admitted write can be reconciled without replay. */
class PrivateInvitationsController(
    private val owner: String, private val sessions: SessionStorage, private val gateway: PrivateInvitationGateway,
    private val scope: CoroutineScope, private val onCreated: () -> Unit, private val onReview: suspend (String) -> Unit,
) {
    private val mutable = MutableStateFlow(PrivateInvitationsState())
    val state = mutable.asStateFlow()
    private var active = true
    private var generation = 0L
    private var inboxGeneration = 0L
    private data class Pending(val circle: String, val request: PrivateInvitationRequestDto)
    private var pending: Pending? = null
    private val watch = scope.launch {
        try { sessions.session.collect { if (it?.userId != owner) deactivate() } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { deactivate() }
    }
    init { refresh() }
    private suspend fun current(token: Long) = active && generation == token && sessions.current()?.userId == owner
    private fun deactivate() { active = false; generation++; pending = null; mutable.value = PrivateInvitationsState(loading = false) }
    fun dispose() { deactivate(); watch.cancel() }
    fun open() { if (active) mutable.update { it.copy(open = true, message = null) } }
    fun dismiss() { mutable.update { it.copy(open = false, email = "", error = null) } }
    fun mode(value: String) { if (editable() && value in setOf("EMAIL", "LINK")) mutable.update { it.copy(mode = value, email = "", error = null) } }
    fun email(value: String) { if (editable()) mutable.update { it.copy(email = value.take(254), error = null) } }
    fun hours(value: Int) { if (editable() && value in setOf(24, 48, 168)) mutable.update { it.copy(hours = value) } }
    private fun editable() = active && !mutable.value.busy && !mutable.value.unknown

    fun create(circle: String?, online: Boolean) {
        if (!editable() || circle == null) return
        if (!online) { mutable.update { it.copy(error = "Mất mạng. Kết nối lại trước khi tạo lời mời.") }; return }
        val s = mutable.value
        val email = s.email.trim().lowercase()
        if (s.mode == "EMAIL" && (!EMAIL.matches(email) || email.any { it.code > 127 })) {
            mutable.update { it.copy(error = "Nhập email hợp lệ, ví dụ ten@example.com.") }; return
        }
        val request = PrivateInvitationRequestDto(UUID.randomUUID().toString(), s.mode, if (s.mode == "EMAIL") email else null, s.hours)
        val operation = Pending(circle, request)
        pending = operation
        val token = generation
        mutable.update { it.copy(busy = true, error = null, message = null) }
        scope.launch {
            try {
                if (!current(token)) return@launch
                val result = gateway.create(owner, circle, request)
                if (!current(token)) return@launch
                if (matches(operation, result)) complete() else uncertain(operation, token)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!active || generation != token) return@launch
                if (e is ApiException && (e.httpStatus ?: 0) in 400..499 && e.httpStatus != 408) {
                    pending = null
                    mutable.update { it.copy(busy = false, error = if (e.httpStatus == 429) "Đã tạo nhiều lời mời. Thử lại sau." else "Chưa tạo được lời mời. Kiểm tra quyền chủ nhóm và email rồi thử lại.") }
                } else uncertain(operation, token)
            }
        }
    }
    private fun matches(p: Pending, i: InviteDto): Boolean = i.id == p.request.requestId && i.circleId == p.circle && i.createdBy == owner &&
        i.recipientRestricted == (p.request.mode == "EMAIL") && InviteLink.create(i.code) != null &&
        runCatching { Instant.parse(i.expiresAt).isAfter(Instant.now()) }.getOrDefault(false)
    private fun complete() {
        pending = null
        mutable.update { it.copy(open = false, busy = false, unknown = false, email = "", error = null,
            message = "Đã tạo lời mời. Email không xác nhận tài khoản hay trạng thái nhận thông báo.") }
        onCreated()
    }
    private suspend fun uncertain(p: Pending, token: Long) {
        mutable.update { it.copy(unknown = true) }
        readBack(p, token)
    }
    private suspend fun readBack(p: Pending, token: Long) {
        try {
            if (!current(token)) return
            val result = gateway.created(owner, p.circle, p.request.requestId)
            if (!current(token)) return
            if (matches(p, result)) { complete(); return }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* only a matching read confirms creation */ }
        if (active && token == generation) mutable.update { it.copy(busy = false, unknown = true,
            error = "Chưa rõ kết quả tạo lời mời. Kiểm tra kết quả; app không tự tạo lại.") }
    }
    fun checkAgain(online: Boolean) {
        val operation = pending ?: return
        if (!active || !online || mutable.value.busy || !mutable.value.unknown) return
        mutable.update { it.copy(busy = true, error = null) }
        scope.launch { readBack(operation, generation) }
    }
    fun refresh() {
        if (!active || mutable.value.detailBusy) return
        val token = generation
        val inboxToken = ++inboxGeneration
        mutable.update { it.copy(loading = true, inboxError = null) }
        scope.launch {
            try {
                if (!current(token)) return@launch
                val result = gateway.received(owner)
                if (current(token) && inboxToken == inboxGeneration) mutable.update { it.copy(loading = false, received = result) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (active && token == generation && inboxToken == inboxGeneration) mutable.update { it.copy(loading = false, received = emptyList(), inboxError = "Chưa tải được lời mời. Kết nối lại rồi tải lại.") }
            }
        }
    }
    fun review(id: String, hide: Boolean = false) {
        if (!active || mutable.value.detailBusy) return
        val token = generation
        ++inboxGeneration
        mutable.update { it.copy(loading = false, detailBusy = true, inboxError = null) }
        scope.launch {
            try {
                if (!current(token)) return@launch
                if (hide) gateway.hide(owner, id) else {
                    val detail = gateway.detail(owner, id)
                    if (!current(token)) return@launch
                    val code = requireNotNull(detail.code)
                    require(detail.id == id && InviteLink.create(code) != null && Instant.parse(detail.expiresAt).isAfter(Instant.now()))
                    onReview(code)
                }
                if (current(token)) {
                    mutable.update { it.copy(detailBusy = false, received = if (hide) it.received.filterNot { entry -> entry.id == id } else it.received) }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (active && token == generation) mutable.update { it.copy(detailBusy = false,
                    inboxError = if (hide) "Chưa rõ kết quả ẩn lời mời. Tải lại để kiểm tra; app không tự gửi lại." else "Không mở được lời mời. Có thể đã hết hạn, bị thu hồi hoặc không dành cho tài khoản này. Tải lại để kiểm tra.") }
            }
        }
    }
    companion object { private val EMAIL = Regex("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+") }
}
