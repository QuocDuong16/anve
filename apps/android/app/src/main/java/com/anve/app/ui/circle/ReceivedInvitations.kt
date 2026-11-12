package com.anve.app.ui.circle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ReceivedInvitations(state: PrivateInvitationsState, onRefresh: () -> Unit, onReview: (String) -> Unit, onHide: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("received-invitations"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Lời mời dành cho bạn", style = MaterialTheme.typography.titleMedium)
        if (state.loading) CircularProgressIndicator()
        else if (state.received.isEmpty() && state.inboxError == null) Text("Chưa có lời mời đang hiệu lực.", style = MaterialTheme.typography.bodyMedium)
        state.inboxError?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        state.received.forEach { invite ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(invite.circleName, style = MaterialTheme.typography.titleSmall)
                Text("${invite.inviterDisplayName} mời bạn tham gia", style = MaterialTheme.typography.bodyMedium)
                val expiry = runCatching { DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.systemDefault()).format(Instant.parse(invite.expiresAt)) }.getOrDefault("chưa rõ")
                Text("Hết hạn: $expiry", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onReview(invite.id) }, enabled = !state.detailBusy,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Xem lời mời") }
                    TextButton(onClick = { onHide(invite.id) }, enabled = !state.detailBusy,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Ẩn với tôi") }
                }
            }
        }
        TextButton(onClick = onRefresh, enabled = !state.loading && !state.detailBusy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tải lại lời mời") }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
