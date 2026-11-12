package com.anve.app.ui.avatar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anve.app.data.api.AvatarStateDto
import com.anve.app.ui.theme.AnVeTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarSheet(state: AvatarUiState, online: Boolean, onDismiss: () -> Unit, onChoose: () -> Unit, onRemove: () -> Unit,
    onSave: () -> Unit, onCheck: () -> Unit, onRetry: () -> Unit, preview: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val editable = !state.loading && !state.processing && !state.busy && !state.unknown && state.metadata != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.86f).padding(horizontal = 16.dp).testTag("avatar-sheet")) {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ảnh đại diện", style = MaterialTheme.typography.headlineSmall)
                    Text("Ảnh hiển thị với người trong nhóm.", style = MaterialTheme.typography.bodyMedium)
                } }
                item { preview() }
                if (state.loading || state.processing) item {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(if (state.processing) "Đang xử lý ảnh…" else "Đang tải thông tin ảnh…")
                }
                item { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onChoose, enabled = editable, modifier = Modifier.weight(1f).widthIn(min = 150.dp).heightIn(min = 48.dp).testTag("avatar-choose"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (dark) colors.onSurface else colors.primary)) { Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(20.dp)); Text("Chọn ảnh khác", Modifier.padding(start = 8.dp)) }
                    OutlinedButton(onClick = onRemove, enabled = editable && !state.removeDraft && (state.selection != null || state.metadata.imageId != null),
                        modifier = Modifier.weight(1f).widthIn(min = 150.dp).heightIn(min = 48.dp).testTag("avatar-remove"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (dark) colors.onSurface else colors.primary)) { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(20.dp)); Text("Xóa ảnh", Modifier.padding(start = 8.dp)) }
                } }
                item { Text("Chỉ ảnh bạn chọn được gửi lên AnVề.", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium) }
                if (state.removeDraft) item { Text("Ảnh hiện tại sẽ được xóa sau khi bạn lưu.") }
                if (!online) item { Text("Mất mạng. Bạn có thể xem trước; cần kết nối để lưu thay đổi.") }
                state.error?.let { item { Text(it, color = colors.onSurface) } }
                if (state.busy) item { Text("Đang chờ máy chủ xác nhận. Đóng bảng không hủy được thao tác đã gửi.") }
            }
            Spacer(Modifier.height(16.dp))
            val actionColors = ButtonDefaults.buttonColors(containerColor = if (dark) colors.primaryContainer else colors.primary,
                contentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary)
            when {
                state.unknown -> Button(onClick = onCheck, enabled = online && !state.busy, colors = actionColors,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("avatar-check")) { Text("Kiểm tra kết quả") }
                state.metadata == null && !state.loading -> Button(onClick = onRetry, enabled = online && !state.busy, colors = actionColors,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Thử lại") }
                else -> Button(onClick = onSave, enabled = editable && state.changed && online, colors = actionColors,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("avatar-save")) { Text(if (state.removeDraft) "Lưu thay đổi" else "Lưu ảnh") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Preview
@Composable
private fun AvatarSheetPreview() {
    AnVeTheme { AvatarSheet(AvatarUiState(open = true, loading = false, metadata = AvatarStateDto(0)), true, {}, {}, {}, {}, {}, {}) {
        AvatarFrame("An", null, Modifier.size(160.dp))
    } }
}
