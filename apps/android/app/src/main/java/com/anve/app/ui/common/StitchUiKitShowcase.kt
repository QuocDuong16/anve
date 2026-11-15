package com.anve.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anve.app.R
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalStitchColors

/**
 * Sample model driving the Stitch Screen 52 UI Kit & Component Library reference showcase.
 */
data class StitchShowcaseSample(
    val memberName: String,
    val memberRole: String,
    val memberLocation: String,
    val memberUpdatedAt: String,
    val placeName: String,
    val placeRadiusMeters: Int,
    val journeyStepOne: String,
    val journeyStepTwo: String,
)

/**
 * Complete Stitch Screen 52 ("AnVe UI Kit & Component Library") reference showcase
 * demonstrating all Warm Tactile Minimalism primitives across light and dark modes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StitchUiKitShowcaseScreen(
    sample: StitchShowcaseSample,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    Surface(
        color = colors.background,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.stitch_uikit_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.primary,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.stitch_uikit_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            // Section 1: Semantic Badges
            item {
                ShowcaseSectionCard(title = stringResource(R.string.stitch_uikit_sec_badges)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StitchSemanticBadge(
                            text = stringResource(R.string.stitch_badge_safe_home),
                            kind = StitchBadgeKind.SAFE_HOME,
                            showDot = true,
                        )
                        StitchSemanticBadge(
                            text = stringResource(R.string.stitch_badge_in_transit),
                            kind = StitchBadgeKind.IN_TRANSIT,
                            icon = Icons.Default.DirectionsCar,
                        )
                        StitchSemanticBadge(
                            text = stringResource(R.string.stitch_badge_at_place),
                            kind = StitchBadgeKind.AT_PLACE,
                            icon = Icons.Default.Home,
                        )
                        StitchSemanticBadge(
                            text = stringResource(R.string.stitch_badge_delayed),
                            kind = StitchBadgeKind.DELAYED_OFFLINE,
                            icon = Icons.Default.WifiOff,
                        )
                    }
                }
            }

            // Section 2: Live Sharing Pill
            item {
                ShowcaseSectionCard(title = stringResource(R.string.stitch_uikit_sec_pill)) {
                    StitchLiveSharingPill(
                        text = stringResource(R.string.home_privacy_live_pill),
                        active = true,
                        actionLabel = stringResource(R.string.tracking_state_active_pill),
                        onClick = {},
                    )
                }
            }

            // Section 3: Family Member Card
            item {
                ShowcaseSectionCard(title = stringResource(R.string.stitch_uikit_sec_member)) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = colors.surfaceContainerLowest,
                        border = BorderStroke(1.dp, stitch.hairline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = colors.primaryContainer,
                                    contentColor = colors.onPrimaryContainer,
                                    border = BorderStroke(2.dp, stitch.livePulse),
                                    modifier = Modifier.size(50.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = sample.memberName.take(1).uppercase(),
                                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = sample.memberName,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    )
                                    Text(
                                        text = sample.memberRole,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                                StitchSemanticBadge(
                                    text = stringResource(R.string.stitch_badge_safe_home),
                                    kind = StitchBadgeKind.SAFE_HOME,
                                    showDot = true,
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = colors.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = sample.memberLocation,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = sample.memberUpdatedAt,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section 4: Safe Place Card
            item {
                ShowcaseSectionCard(title = stringResource(R.string.stitch_uikit_sec_place)) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = colors.surfaceContainerLowest,
                        border = BorderStroke(1.dp, stitch.hairline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StitchCategoryIconBox(
                                    icon = Icons.Default.School,
                                    containerColor = stitch.primaryFixed,
                                    contentColor = stitch.onPrimaryFixed,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = sample.placeName,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    )
                                    Text(
                                        text = stringResource(R.string.places_radius_bar_label, sample.placeRadiusMeters),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                                StitchSemanticBadge(
                                    text = stringResource(R.string.places_edit_short),
                                    kind = StitchBadgeKind.NEUTRAL,
                                )
                            }
                        }
                    }
                }
            }

            // Section 5: Journey Metrics
            item {
                ShowcaseSectionCard(title = stringResource(R.string.stitch_uikit_sec_metrics)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StitchMetricTile(
                            label = stringResource(R.string.history_metric_points),
                            value = "18",
                            icon = Icons.Default.LocationOn,
                            modifier = Modifier.weight(1f),
                        )
                        StitchMetricTile(
                            label = stringResource(R.string.history_metric_delayed),
                            value = "1",
                            icon = Icons.Default.Schedule,
                            modifier = Modifier.weight(1f),
                        )
                        StitchMetricTile(
                            label = stringResource(R.string.history_metric_retention),
                            value = "30",
                            icon = Icons.Default.Shield,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Section 6: Vertical Timeline
            item {
                ShowcaseSectionCard(title = stringResource(R.string.stitch_uikit_sec_timeline)) {
                    Column {
                        StitchTimelineItem(
                            icon = Icons.Default.CheckCircle,
                            nodeContainerColor = colors.primaryContainer,
                            nodeContentColor = colors.onPrimaryContainer,
                            isLast = false,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = colors.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = sample.journeyStepOne,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                        StitchTimelineItem(
                            icon = Icons.Default.Home,
                            nodeContainerColor = stitch.primaryFixed,
                            nodeContentColor = stitch.onPrimaryFixed,
                            isLast = true,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = colors.surfaceContainerLowest,
                                border = BorderStroke(1.dp, stitch.hairline),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = sample.journeyStepTwo,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Section 7: Empty & Loading States
            item {
                ShowcaseSectionCard(title = stringResource(R.string.stitch_uikit_sec_states)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StitchLoadingSkeletonCard(rows = 2)
                        StitchEmptyStateCard(
                            title = stringResource(R.string.history_empty_warm_title),
                            body = stringResource(R.string.history_empty_warm_body),
                        )
                    }
                }
            }

            // Section 8: Reassurance Footer
            item {
                ShowcaseSectionCard(title = stringResource(R.string.stitch_uikit_sec_footer)) {
                    StitchReassuranceFooter(
                        quote = stringResource(R.string.map_reassurance_footer),
                        caption = stringResource(R.string.signin_trust_badge),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowcaseSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, stitch.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            HorizontalDivider(color = stitch.hairline.copy(alpha = 0.7f))
            content()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StitchUiKitShowcaseLightPreview() {
    AnVeTheme(darkTheme = false) {
        StitchUiKitShowcaseScreen(
            sample = StitchShowcaseSample(
                memberName = "Minh Anh",
                memberRole = "Thành viên gia đình",
                memberLocation = "Nhà · Vừa cập nhật",
                memberUpdatedAt = "Sai lệch khoảng 12 m",
                placeName = "Trường Tiểu học Nguyễn Du",
                placeRadiusMeters = 200,
                journeyStepOne = "07:15 · Rời nhà đến trường",
                journeyStepTwo = "07:40 · Đã tới cổng trường an toàn",
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StitchUiKitShowcaseDarkPreview() {
    AnVeTheme(darkTheme = true) {
        StitchUiKitShowcaseScreen(
            sample = StitchShowcaseSample(
                memberName = "Bố Tuấn",
                memberRole = "Chủ nhóm",
                memberLocation = "Cơ quan · Đang di chuyển",
                memberUpdatedAt = "Cập nhật 2 phút trước",
                placeName = "Nhà mình",
                placeRadiusMeters = 150,
                journeyStepOne = "17:30 · Rời cơ quan",
                journeyStepTwo = "18:05 · Về đến nhà an toàn",
            ),
        )
    }
}
