package com.anve.app.ui.common

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anve.app.R
import com.anve.app.data.api.LowBatteryAlertSummaryDto
import com.anve.app.data.api.PlaceDto
import com.anve.app.ui.map.PlaceMatch
import com.anve.app.ui.map.findMatchingPlace
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalEmergencyColors
import com.anve.app.ui.theme.LocalStitchColors

/**
 * Privately matches a low-battery alert's captured coordinates against saved
 * family places on-device without invoking platform reverse geocoding.
 */
fun resolveLowBatteryPlaceMatch(alert: LowBatteryAlertSummaryDto, places: List<PlaceDto>): PlaceMatch? =
    findMatchingPlace(alert.latitude, alert.longitude, places)

@Composable
private fun lowBatteryAlertColors(isCritical: Boolean): Pair<Color, Color> {
    val emergency = LocalEmergencyColors.current
    val stitch = LocalStitchColors.current
    return if (isCritical) {
        emergency.emergencyContainer to emergency.onEmergencyContainer
    } else {
        stitch.transitContainer to stitch.onTransitContainer
    }
}

@Composable
fun LowBatteryAlertPill(
    alert: LowBatteryAlertSummaryDto,
    modifier: Modifier = Modifier,
) {
    val isCritical = alert.threshold <= 5 || alert.batteryPercent <= 5
    val (container, ink) = lowBatteryAlertColors(isCritical)
    val clock = AnveTime.clock(alert.recordedAt, "")
    val label = if (clock.isNotBlank()) {
        stringResource(R.string.low_battery_pill_with_time, alert.batteryPercent, clock)
    } else {
        stringResource(R.string.low_battery_pill_no_time, alert.batteryPercent)
    }

    Surface(
        shape = CircleShape,
        color = container,
        contentColor = ink,
        border = BorderStroke(1.dp, ink.copy(alpha = 0.16f)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.BatteryAlert,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
        }
    }
}

@Composable
fun LowBatteryAlertBanner(
    alert: LowBatteryAlertSummaryDto,
    places: List<PlaceDto>,
    modifier: Modifier = Modifier,
) {
    val isCritical = alert.threshold <= 5 || alert.batteryPercent <= 5
    val (container, ink) = lowBatteryAlertColors(isCritical)
    val clock = AnveTime.clock(alert.recordedAt, "")
    val title = if (clock.isNotBlank()) {
        stringResource(R.string.low_battery_banner_title_with_time, alert.batteryPercent, clock)
    } else {
        stringResource(R.string.low_battery_banner_title_no_time, alert.batteryPercent)
    }
    val placeMatch = resolveLowBatteryPlaceMatch(alert, places)
    val placePhrase = placeMatch?.let { match ->
        if (match.isInside) {
            stringResource(R.string.low_battery_place_at, match.place.name)
        } else {
            stringResource(R.string.low_battery_place_near, match.place.name)
        }
    }
    val body = when {
        isCritical && placePhrase != null -> stringResource(R.string.low_battery_banner_critical_place, placePhrase)
        isCritical -> stringResource(R.string.low_battery_banner_critical_no_place)
        placePhrase != null -> stringResource(R.string.low_battery_banner_warning_place, placePhrase)
        else -> stringResource(R.string.low_battery_banner_warning_no_place)
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = container,
        contentColor = ink,
        border = BorderStroke(1.dp, ink.copy(alpha = 0.16f)),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.BatteryAlert,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private val previewCriticalAlert = LowBatteryAlertSummaryDto(
    id = "preview-alert-5",
    threshold = 5,
    batteryPercent = 5,
    recordedAt = "2026-11-19T07:20:00Z",
    latitude = 10.7769,
    longitude = 106.7009,
    horizontalAccuracyMeters = 15.0,
    locationRecordedAt = "2026-11-19T07:19:50Z",
)

private val previewWarningAlert = LowBatteryAlertSummaryDto(
    id = "preview-alert-15",
    threshold = 15,
    batteryPercent = 15,
    recordedAt = "2026-11-19T07:10:00Z",
)

private val previewPlaces = listOf(
    PlaceDto(
        id = "place-school",
        ownerId = "owner",
        circleId = "circle",
        name = "Trường THCS",
        latitude = 10.7769,
        longitude = 106.7009,
        radiusMeters = 150.0,
        createdAt = "2026-11-01T00:00:00Z",
        updatedAt = "2026-11-01T00:00:00Z",
        sharedWithCircle = true,
    ),
)

@Preview(name = "1. Standard Light", showBackground = true)
@Composable
private fun LowBatteryAlertUiPreview() {
    AnVeTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LowBatteryAlertPill(alert = previewCriticalAlert)
            LowBatteryAlertBanner(alert = previewCriticalAlert, places = previewPlaces)
            LowBatteryAlertBanner(alert = previewWarningAlert, places = emptyList())
        }
    }
}

@Preview(name = "2. Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LowBatteryAlertUiDarkPreview() {
    AnVeTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LowBatteryAlertPill(alert = previewCriticalAlert)
            LowBatteryAlertBanner(alert = previewCriticalAlert, places = previewPlaces)
        }
    }
}

@Preview(name = "3. Elderly 200% Font", fontScale = 2.0f, showBackground = true)
@Composable
private fun LowBatteryAlertUiElderlyFontPreview() {
    AnVeTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LowBatteryAlertPill(alert = previewCriticalAlert)
            LowBatteryAlertBanner(alert = previewCriticalAlert, places = previewPlaces)
        }
    }
}
