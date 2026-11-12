package com.anve.app.ui.circle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anve.app.data.api.InvitePreviewDto
import com.anve.app.ui.theme.AnVeTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun InvitationDialog(state: InvitationUiState, online: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit, onRetry: () -> Unit, onCheck: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val action = if (dark) colors.onSurface else colors.primary
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Tham gia nhóm?") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("invitation-details"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.loading) {
                    CircularProgressIndicator()
                    Text("Đang kiểm tra lời mời với máy chủ…")
                }
                state.preview?.let { preview ->
                    Text(preview.circleName, style = MaterialTheme.typography.headlineSmall)
                    Text("Lời mời dùng một lần.")
                    val expiry = runCatching { DateTimeFormatter.ofPattern("dd/MM, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(preview.expiresAt)) }.getOrDefault("Chưa đọc được thời hạn")
                    Text("Hết hạn: $expiry")
                }
                Surface(color = if (dark) colors.secondary else colors.secondaryContainer, contentColor = if (dark) colors.onSecondary else colors.onSecondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Info, null)
                        Text("Tham gia nhóm không tự bật chia sẻ vị trí.", Modifier.weight(1f))
                    }
                }
                if (state.accountLabel.isNotEmpty()) Text("Đang đăng nhập: ${state.accountLabel}")
                if (!online) Text("Mất mạng. Kết nối lại để kiểm tra hoặc xác nhận lời mời.")
                state.error?.let { Text(it, color = colors.onSurface, style = MaterialTheme.typography.bodyMedium) }
                if (state.busy) Text("Đang chờ máy chủ xác nhận…")
            }
        },
        confirmButton = {
            when {
                state.unknown -> TextButton(onClick = onCheck, enabled = online && !state.busy, colors = ButtonDefaults.textButtonColors(contentColor = action)) { Text("Kiểm tra kết quả") }
                state.retryable -> TextButton(onClick = onRetry, enabled = online && !state.loading && !state.busy, colors = ButtonDefaults.textButtonColors(contentColor = action)) { Text("Thử lại") }
                state.preview != null && !state.blocked -> Button(onClick = onConfirm, enabled = online && !state.loading && !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = if (dark) colors.primaryContainer else colors.primary, contentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary)) { Text("Tham gia nhóm") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Để sau", color = action) } },
    )
}

@Preview
@Composable
private fun InvitationPreview() {
    AnVeTheme { InvitationDialog(InvitationUiState(loading = false, preview = InvitePreviewDto("example", "Nhà mình", "2026-11-14T13:00:00Z"), accountLabel = "Dương · duong@example.test"), true, {}, {}, {}, {}) }
}
