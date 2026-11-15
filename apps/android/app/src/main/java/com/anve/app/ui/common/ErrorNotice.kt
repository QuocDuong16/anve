package com.anve.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anve.app.R
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalStitchColors

/**
 * The app-wide failure presentation: an errorContainer card rather than bare
 * error-colored text, so every failed state carries the same visual weight.
 * The sign-in and home screens established the pattern; these screens reuse
 * it instead of re-deciding it. Retry stays optional because some failures
 * already have their recovery elsewhere.
 *
 * The message text marks itself as a polite live region inside the component,
 * so a failure that appears on any surface using it is announced by screen
 * readers without each call site re-declaring the semantics. Dismiss stays
 * optional next to retry for surfaces where acknowledging the failure is the
 * recovery, not repeating an action.
 */
@Composable
fun AnveErrorNotice(
    text: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.30f)),
        colors = CardDefaults.cardColors(
            containerColor = colors.errorContainer,
            contentColor = colors.onErrorContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.error.copy(alpha = 0.14f),
                    contentColor = colors.onErrorContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (onRetry != null || onDismiss != null) {
                HorizontalDivider(color = colors.error.copy(alpha = 0.14f))
            }
            when {
                onRetry != null && onDismiss != null -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RetryAction(stringResource(R.string.action_retry), onRetry)
                    NoticeAction(stringResource(R.string.action_dismiss), onDismiss)
                }
                onRetry != null -> RetryAction(stringResource(R.string.action_retry), onRetry)
                onDismiss != null -> NoticeAction(stringResource(R.string.action_dismiss), onDismiss)
            }
        }
    }
}

/** Consistent warm card header with an icon badge, heading semantics, and optional status pill. */
@Composable
fun AnVeSectionHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badgeText: String? = null,
    badgeContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    badgeContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    iconContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StitchCategoryIconBox(
            icon = icon,
            size = 42.dp,
            iconSize = 20.dp,
            cornerRadius = 14.dp,
            containerColor = iconContainerColor,
            contentColor = iconContentColor,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (badgeText != null) {
            AnVeStatusPill(
                text = badgeText,
                containerColor = badgeContainerColor,
                contentColor = badgeContentColor,
            )
        }
    }
}

/** Compact pill badge for state, role, freshness, and sync labels. */
@Composable
fun AnVeStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    border: BorderStroke? = BorderStroke(0.5.dp, LocalStitchColors.current.hairline.copy(alpha = 0.6f)),
) {
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = border,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/** Primary error recovery action inside notices. */
@Composable
private fun RetryAction(label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FilledTonalButton(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.surface.copy(alpha = 0.94f),
            contentColor = colors.onErrorContainer,
        ),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

/** Single notice action; 48dp floor keeps the touch target in one place. */
@Composable
private fun NoticeAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label)
    }
}

@Preview(showBackground = true)
@Composable
private fun AnveErrorNoticePreview() {
    AnVeTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AnveErrorNotice("Không thể tải dữ liệu. Vui lòng thử lại.", onRetry = {}, onDismiss = {})
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AnveErrorNoticeDarkPreview() {
    AnVeTheme(darkTheme = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AnveErrorNotice("Không thể tải dữ liệu. Vui lòng thử lại.", onRetry = {})
        }
    }
}
