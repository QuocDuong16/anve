package com.anve.app.ui.avatar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anve.app.R
import com.anve.app.data.avatar.AvatarGateway
import com.anve.app.data.avatar.decodeAvatarImage
import com.anve.app.ui.theme.LocalStitchColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AvatarImage(
    owner: String,
    subject: String,
    imageId: String?,
    name: String,
    gateway: AvatarGateway,
    modifier: Modifier = Modifier,
    retryable: Boolean = false,
    imageContentDescription: String? = null,
) {
    var image by remember(owner, subject, imageId) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(owner, subject, imageId) { mutableStateOf(imageId != null) }
    var failed by remember(owner, subject, imageId) { mutableStateOf(false) }
    var attempt by remember(owner, subject, imageId) { mutableStateOf(0) }
    // The semantics block is not composable, so the descriptions resolve here.
    val failedDescription = stringResource(R.string.avatar_image_failed_desc)
    val retryDescription = stringResource(R.string.avatar_retry_content_desc)
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
    AvatarFrame(name, image, modifier.semantics { if (failed) contentDescription = failedDescription }, imageContentDescription = imageContentDescription) {
        if (failed && retryable) Surface(Modifier.align(Alignment.BottomEnd).size(48.dp), shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            IconButton(onClick = { attempt++ }) { Icon(Icons.Outlined.Refresh, retryDescription) }
        }
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.BottomEnd).size(16.dp), strokeWidth = 2.dp)
    }
}

@Composable
fun AvatarFrame(
    name: String,
    image: ImageBitmap?,
    modifier: Modifier = Modifier,
    imageContentDescription: String? = null,
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val stitch = LocalStitchColors.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = stitch.primaryFixed,
            contentColor = stitch.onPrimaryFixed,
            border = BorderStroke(2.dp, stitch.primaryFixedDim.copy(alpha = 0.65f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (image != null) {
                    Image(image, imageContentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(
                        text = name.take(1).uppercase().ifBlank { "?" },
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
        // Recovery controls retain their full target outside the photo's circular clip.
        overlay()
    }
}

/**
 * Compact 48dp circular account entry displaying the signed-in user's avatar
 * (or person icon fallback before profile metadata loads).
 */
@Composable
fun AccountAvatarButton(
    ownerId: String?,
    imageId: String?,
    displayName: String,
    gateway: AvatarGateway?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 40.dp,
    elevated: Boolean = false,
) {
    val accountLabel = stringResource(R.string.tab_account)
    val stitch = LocalStitchColors.current
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = accountLabel },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = if (elevated) stitch.cardTactile.copy(alpha = 0.94f) else stitch.cardTactile,
            border = BorderStroke(1.dp, stitch.hairline),
            modifier = Modifier.size(avatarSize),
        ) {
            if (ownerId != null && gateway != null && (imageId != null || displayName.isNotBlank())) {
                AvatarImage(
                    owner = ownerId,
                    subject = ownerId,
                    imageId = imageId,
                    name = displayName,
                    gateway = gateway,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
