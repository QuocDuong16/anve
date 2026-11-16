package com.anve.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anve.app.R
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalEmergencyColors
import com.anve.app.ui.theme.LocalStitchColors

/** Single-column reading/form max width for large screens and tablets (DESIGN.md). */
internal val ANVE_MAX_CONTENT_WIDTH = 600.dp

/** Caps content width on tablets while staying full-width on compact phones. */
internal fun Modifier.anveContentWidth(): Modifier = widthIn(max = ANVE_MAX_CONTENT_WIDTH)

/** Centered loading placeholder while session or owner identity resolves. */
@Composable
fun AnVeCheckingState(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Semantic status variants defined in Stitch Screen 52 ("AnVe UI Kit").
 */
enum class StitchBadgeKind {
    SAFE_HOME,
    IN_TRANSIT,
    AT_PLACE,
    DELAYED_OFFLINE,
    EMERGENCY,
    NEUTRAL,
}

/**
 * Concentric status indicator dot with a soft outer halo representing live or
 * paused location sharing without relying on jarring bright neon accents.
 */
@Composable
fun StitchLivePulseDot(
    active: Boolean,
    modifier: Modifier = Modifier,
    color: Color = if (active) LocalStitchColors.current.livePulse else MaterialTheme.colorScheme.outline,
    haloSize: Dp = 18.dp,
    coreSize: Dp = 8.dp,
) {
    Box(
        modifier = modifier.size(haloSize),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(haloSize)
                .clip(CircleShape)
                .background(color.copy(alpha = if (active) 0.22f else 0.14f)),
        )
        Box(
            modifier = Modifier
                .size(coreSize)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * Semantic badge/tag pill following Stitch's Warm Tactile Minimalism palette.
 */
@Composable
fun StitchSemanticBadge(
    text: String,
    kind: StitchBadgeKind,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    showDot: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val emergency = LocalEmergencyColors.current
    val (bg, fg, dotColor) = when (kind) {
        StitchBadgeKind.SAFE_HOME -> Triple(stitch.liveContainer, stitch.onLiveContainer, stitch.livePulse)
        StitchBadgeKind.IN_TRANSIT -> Triple(stitch.transitContainer, stitch.onTransitContainer, stitch.onTransitContainer)
        StitchBadgeKind.AT_PLACE -> Triple(colors.secondaryContainer, colors.onSecondaryContainer, colors.secondary)
        StitchBadgeKind.DELAYED_OFFLINE -> Triple(colors.surfaceVariant, colors.onSurfaceVariant, colors.outline)
        StitchBadgeKind.EMERGENCY -> Triple(emergency.emergencyContainer, emergency.onEmergencyContainer, emergency.emergency)
        StitchBadgeKind.NEUTRAL -> Triple(colors.surfaceContainerHigh, colors.onSurface, colors.primary)
    }
    Surface(
        shape = CircleShape,
        color = bg,
        contentColor = fg,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showDot) {
                StitchLivePulseDot(active = kind == StitchBadgeKind.SAFE_HOME, color = dotColor, haloSize = 14.dp, coreSize = 6.dp)
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Full-width Stitch pinned sharing status banner / pill with pulse indicator,
 * clear message copy, and optional trailing pill affordance.
 */
@Composable
fun StitchLiveSharingPill(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val container = if (active) stitch.cardTactile else colors.surfaceContainerLow
    val border = if (active) {
        BorderStroke(1.dp, stitch.livePulse.copy(alpha = 0.30f))
    } else {
        BorderStroke(1.dp, stitch.hairline)
    }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StitchLivePulseDot(active = active)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionLabel != null) {
                Surface(
                    shape = CircleShape,
                    color = if (active) stitch.liveContainer else colors.surfaceContainerHigh,
                    contentColor = if (active) stitch.onLiveContainer else colors.onSurfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            border = border,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            content = content,
        )
    } else {
        Surface(
            shape = CircleShape,
            color = container,
            border = border,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            content = content,
        )
    }
}

/**
 * Compact flat metric column displaying a concise label and prominent value
 * inside cards or detail sections without nested card borders.
 */
@Composable
fun AnVeMetricColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Tactile vertical timeline row with connector line, circular indicator node, and
 * flexible right-hand card content (Stitch Screens 55 & 56).
 */
@Composable
fun StitchTimelineItem(
    icon: ImageVector,
    nodeContainerColor: Color,
    nodeContentColor: Color,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    railColor: Color = LocalStitchColors.current.hairline,
    cardContent: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .width(32.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = nodeContainerColor,
                contentColor = nodeContentColor,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                modifier = Modifier.size(30.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .padding(vertical = 4.dp)
                        .background(railColor),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 12.dp),
            content = cardContent,
        )
    }
}

/**
 * Tactile empty-state card with optional custom illustration slot and primary CTA.
 */
@Composable
fun StitchEmptyStateCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.LocationOn,
    illustration: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = stitch.cardTactile,
        border = BorderStroke(1.dp, stitch.hairline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (illustration != null) {
                illustration()
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                FilledTonalButton(
                    onClick = onAction,
                    shape = CircleShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.primaryContainer,
                        contentColor = colors.onPrimaryContainer,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/**
 * Quiet warm paper skeleton card shown alongside linear progress during reads.
 */
@Composable
fun StitchLoadingSkeletonCard(
    modifier: Modifier = Modifier,
    rows: Int = 2,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = stitch.cardTactile,
        border = BorderStroke(1.dp, stitch.hairline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceContainerHigh),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(14.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceContainerHigh),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.35f)
                            .height(10.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceContainerLow),
                    )
                }
            }
            repeat(rows) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (index % 2 == 0) 0.92f else 0.72f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceContainerLow),
                )
            }
        }
    }
}

/**
 * Compact pill-shaped emergency trigger matching AnVề's calm SOS styling
 * (soft blush surface + urgency dot, 48dp touch target via Material 3).
 */
@Composable
fun AnVeSosPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val emergency = LocalEmergencyColors.current
    val label = stringResource(R.string.tab_sos)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = emergency.emergencyContainer,
        contentColor = emergency.onEmergencyContainer,
        border = BorderStroke(1.dp, emergency.emergency.copy(alpha = 0.28f)),
        shadowElevation = 3.dp,
        tonalElevation = 2.dp,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(emergency.emergency),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                maxLines = 1,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StitchComponentsLightPreview() {
    AnVeTheme(darkTheme = false) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StitchSemanticBadge(stringResource(R.string.stitch_badge_safe_home), StitchBadgeKind.SAFE_HOME, showDot = true)
                StitchSemanticBadge(stringResource(R.string.stitch_badge_in_transit), StitchBadgeKind.IN_TRANSIT)
                StitchSemanticBadge(stringResource(R.string.stitch_badge_at_place), StitchBadgeKind.AT_PLACE, icon = Icons.Outlined.Home)
            }
            StitchLiveSharingPill(
                text = stringResource(R.string.home_privacy_live_pill),
                active = true,
                actionLabel = stringResource(R.string.tracking_start),
                onClick = {},
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AnVeMetricColumn(
                    label = stringResource(R.string.history_metric_points),
                    value = "4",
                    modifier = Modifier.weight(1f),
                )
                AnVeMetricColumn(
                    label = stringResource(R.string.history_metric_retention),
                    value = "30",
                    modifier = Modifier.weight(1f),
                )
            }
            StitchLoadingSkeletonCard()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StitchComponentsDarkPreview() {
    AnVeTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StitchLiveSharingPill(
                text = stringResource(R.string.home_privacy_live_pill),
                active = false,
            )
            StitchEmptyStateCard(
                title = stringResource(R.string.history_empty_warm_title),
                body = stringResource(R.string.history_empty_warm_body),
            )
        }
    }
}
