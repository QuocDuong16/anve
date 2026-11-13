package com.anve.app.ui.avatar

import com.anve.app.data.api.ApiException
import com.anve.app.data.api.AvatarStateDto
import com.anve.app.data.auth.SessionStorage
import com.anve.app.data.avatar.AvatarGateway
import com.anve.app.data.avatar.AvatarRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AvatarUiState(
    val open: Boolean = false,
    val loading: Boolean = true,
    val processing: Boolean = false,
    val busy: Boolean = false,
    val metadata: AvatarStateDto? = null,
    val selection: ByteArray? = null,
    val removeDraft: Boolean = false,
    val unknown: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val changed: Boolean get() = selection != null || (removeDraft && metadata?.imageId != null)
    override fun toString() = "AvatarUiState(open=$open, busy=$busy, selected=${selection != null}, unknown=$unknown)"
}

/** Screen-local ownership: disposal/rotation drops drafts, never submits them. */
class AvatarController(
    private val owner: String,
    private val sessions: SessionStorage,
    private val gateway: AvatarGateway,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(AvatarUiState())
    val state = mutable.asStateFlow()
    private var active = true
    private var sequence = 0L
    private var picker: Long? = null
    private var work: Job? = null
    private data class Pending(val id: String, val revision: Long, val removal: Boolean)
    private var pending: Pending? = null
    private val sessionWatch = scope.launch {
        try { sessions.session.collect { if (it?.userId != owner) deactivate() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { deactivate() }
    }

    init { refresh() }

    private suspend fun current(revision: Long): Boolean = active && sequence == revision && sessions.current()?.userId == owner

    fun deactivate() {
        active = false
        sequence++
        picker = null
        pending = null
        work?.cancel()
        mutable.value = AvatarUiState(loading = false)
    }

    fun dispose() { deactivate(); sessionWatch.cancel() }

    fun open() {
        if (!active) return
        mutable.update { it.copy(open = true) }
        if (mutable.value.metadata == null && !mutable.value.loading && !mutable.value.unknown) refresh()
    }

    fun dismiss() {
        picker = null
        if (mutable.value.processing) { sequence++; work?.cancel() }
        mutable.update { it.copy(open = false, selection = null, removeDraft = false, processing = false) }
    }

    fun refresh() {
        if (!active || mutable.value.busy || mutable.value.processing || mutable.value.unknown) return
        val revision = ++sequence
        work?.cancel()
        mutable.update { it.copy(loading = true, error = null) }
        work = scope.launch {
            try {
                if (!current(revision)) return@launch
                val result = gateway.state(owner).checked()
                if (current(revision)) mutable.update { it.copy(loading = false, metadata = result) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (active && revision == sequence) mutable.update { it.copy(loading = false, error = "Chưa tải được ảnh đại diện. Kiểm tra mạng rồi thử lại.") }
            }
        }
    }

    fun beginPicker(): Long? {
        val s = mutable.value
        if (!active || s.loading || s.processing || s.busy || s.unknown || s.metadata == null) return null
        return (++sequence).also { picker = it }
    }

    fun picked(ticket: Long, prepare: suspend () -> ByteArray?) {
        if (!active || picker != ticket || sequence != ticket) return
        picker = null
        mutable.update { it.copy(processing = true, error = null, message = null) }
        work = scope.launch {
            try {
                if (!current(ticket)) return@launch
                val bytes = prepare()
                if (!current(ticket)) return@launch
                if (bytes != null) {
                    require(bytes.isNotEmpty() && bytes.size <= AvatarRepository.MAX_AVATAR_BYTES)
                    mutable.update { it.copy(selection = bytes, removeDraft = false) }
                }
                mutable.update { it.copy(processing = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (active && ticket == sequence) mutable.update { it.copy(processing = false, error = "Không đọc được ảnh này. Chọn một ảnh khác hoặc ảnh nhỏ hơn.") }
            }
        }
    }

    fun pickerUnavailable(ticket: Long) {
        if (!active || picker != ticket) return
        picker = null
        mutable.update { it.copy(error = "Không mở được trình chọn ảnh. Thử lại hoặc kiểm tra ứng dụng hệ thống.") }
    }

    fun chooseRemoval() {
        val s = mutable.value
        if (!active || s.loading || s.busy || s.processing || s.unknown) return
        picker = null
        sequence++
        mutable.update { it.copy(selection = null, removeDraft = it.metadata?.imageId != null, error = null, message = null) }
    }

    fun save(online: Boolean) {
        val s = mutable.value
        if (!active || s.loading || s.processing || s.busy || s.unknown || !s.changed) return
        if (!online) { mutable.update { it.copy(error = "Mất mạng. Ảnh chưa được gửi; kết nối lại trước khi lưu.") }; return }
        val metadata = s.metadata ?: return
        val operation = Pending(UUID.randomUUID().toString(), metadata.revision, s.removeDraft)
        val bytes = s.selection
        pending = operation
        picker = null
        val revision = ++sequence
        mutable.update { it.copy(busy = true, error = null, message = null) }
        work = scope.launch {
            try {
                if (!current(revision)) return@launch
                val result = if (operation.removal) gateway.remove(owner, operation.id, operation.revision)
                else gateway.replace(owner, operation.id, operation.revision, requireNotNull(bytes))
                result.checked()
                if (!current(revision)) return@launch
                if (matches(operation, result)) complete(result, operation.removal)
                else { mutable.update { it.copy(unknown = true) }; readBack(operation, revision) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!active || revision != sequence) return@launch
                if (e is ApiException && (e.httpStatus ?: 0) in 400..499 && e.httpStatus != 408) {
                    if (e.code == "AVATAR_REVISION_CONFLICT") {
                        mutable.update { it.copy(unknown = true) }
                        readBack(operation, revision)
                    } else {
                        pending = null
                        mutable.update { it.copy(busy = false, error = "Chưa lưu được ảnh. Kiểm tra ảnh, tài khoản hoặc thử lại sau.") }
                    }
                } else {
                    mutable.update { it.copy(unknown = true) }
                    readBack(operation, revision)
                }
            }
        }
    }

    fun checkAgain(online: Boolean) {
        val operation = pending ?: return
        if (!active || !online || mutable.value.busy || !mutable.value.unknown) return
        val revision = ++sequence
        mutable.update { it.copy(busy = true, error = null) }
        work = scope.launch { readBack(operation, revision) }
    }

    private fun matches(operation: Pending, result: AvatarStateDto): Boolean =
        result.revision == operation.revision + 1 && result.operationId == operation.id &&
            if (operation.removal) result.imageId == null else result.imageId == operation.id

    private suspend fun readBack(operation: Pending, revision: Long) {
        try {
            if (!current(revision)) return
            val result = gateway.state(owner).checked()
            if (!current(revision)) return
            when {
                matches(operation, result) -> complete(result, operation.removal)
                result.revision > operation.revision -> {
                    pending = null
                    mutable.update { it.copy(busy = false, unknown = false, metadata = result, selection = null, removeDraft = false,
                        error = "Ảnh hiện tại đã thay đổi. Kiểm tra trước khi chọn thao tác mới.") }
                }
                else -> mutable.update { it.copy(busy = false, unknown = true, error = "Máy chủ chưa xác nhận thay đổi ảnh. Kiểm tra kết quả; app không tự gửi lại.") }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (active && revision == sequence) mutable.update { it.copy(busy = false, unknown = true,
                error = "Máy chủ chưa xác nhận thay đổi ảnh. Kết nối lại rồi kiểm tra kết quả.") }
        }
    }

    private fun complete(result: AvatarStateDto, removal: Boolean) {
        pending = null
        mutable.value = AvatarUiState(loading = false, metadata = result, message = if (removal) "Đã xóa ảnh đại diện." else "Đã cập nhật ảnh đại diện.")
    }
}
