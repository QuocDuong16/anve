package com.anve.app.ui.avatar

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anve.app.AppContainer
import com.anve.app.data.avatar.decodeAvatarImage
import com.anve.app.data.avatar.preparePickedAvatar

@Composable
fun AvatarEditorHost(container: AppContainer, owner: String, name: String, content: @Composable (AvatarController, AvatarUiState) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val controller = remember(owner) { AvatarController(owner, container.sessionStore, container.avatarRepository, scope) }
    val state by controller.state.collectAsStateWithLifecycle()
    val tracking by container.trackingManager.status.collectAsStateWithLifecycle()
    var pickerTicket by remember(owner) { mutableStateOf<Long?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val ticket = pickerTicket
        pickerTicket = null
        if (ticket != null) controller.picked(ticket) { uri?.let { preparePickedAvatar(context.contentResolver, it) } }
    }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    content(controller, state)
    if (state.open) {
        val selected = remember(state.selection) { state.selection?.let { decodeAvatarImage(it)?.asImageBitmap() } }
        AvatarSheet(state, tracking.online, controller::dismiss, onChoose = {
            val ticket = controller.beginPicker()
            if (ticket != null) {
                pickerTicket = ticket
                try { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                catch (_: RuntimeException) { pickerTicket = null; controller.pickerUnavailable(ticket) }
            }
        }, onRemove = controller::chooseRemoval, onSave = { controller.save(tracking.online) },
            onCheck = { controller.checkAgain(tracking.online) }, onRetry = controller::refresh) {
            if (selected != null || state.removeDraft || state.metadata?.imageId == null) AvatarFrame(name, selected, Modifier.size(160.dp))
            else AvatarImage(owner, owner, state.metadata?.imageId, name, container.avatarRepository, Modifier.size(160.dp), retryable = true)
        }
    }
}
