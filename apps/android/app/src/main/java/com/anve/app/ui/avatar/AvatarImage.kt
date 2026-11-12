package com.anve.app.ui.avatar

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.anve.app.data.avatar.AvatarGateway
import com.anve.app.data.avatar.decodeAvatarImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AvatarImage(owner: String, subject: String, imageId: String?, name: String, gateway: AvatarGateway, modifier: Modifier = Modifier, retryable: Boolean = false) {
    var image by remember(owner, subject, imageId) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(owner, subject, imageId) { mutableStateOf(imageId != null) }
    var failed by remember(owner, subject, imageId) { mutableStateOf(false) }
    var attempt by remember(owner, subject, imageId) { mutableStateOf(0) }
    LaunchedEffect(owner, subject, imageId, attempt) {
        if (imageId == null) return@LaunchedEffect
        loading = true
        failed = false
        try {
            val bytes = gateway.image(owner, subject, imageId)
            val bitmap = withContext(Dispatchers.Default) { decodeAvatarImage(bytes)?.asImageBitmap() }
            image = bitmap
            failed = bitmap == null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        loading = false
    }
    AvatarFrame(name, image, modifier.semantics { if (failed) contentDescription = "Chưa tải được ảnh đại diện; đang hiển thị chữ cái." }) {
        if (failed && retryable) Surface(Modifier.align(Alignment.BottomEnd).size(48.dp), shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            IconButton(onClick = { attempt++ }) { Icon(Icons.Outlined.Refresh, "Tải lại ảnh đại diện") }
        }
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.BottomEnd).size(16.dp), strokeWidth = 2.dp)
    }
}

@Composable
fun AvatarFrame(name: String, image: ImageBitmap?, modifier: Modifier = Modifier, overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxSize(), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Box(contentAlignment = Alignment.Center) {
                if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(name.take(1).uppercase().ifBlank { "?" }, style = MaterialTheme.typography.titleLarge)
            }
        }
        // Recovery controls retain their full target outside the photo's circular clip.
        overlay()
    }
}
