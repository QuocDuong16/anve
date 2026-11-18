package com.anve.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.anve.app.R
import com.anve.app.data.api.PlaceDto
import com.anve.app.tracking.distanceMeters
import com.anve.app.ui.theme.LocalStitchColors
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.MapProperties
import kotlin.math.roundToInt

/**
 * Shared Warm Tactile / Forest Dark Google Maps properties hiding noisy
 * business/transit POIs so family markers, places and routes stay legible.
 */
@Composable
fun rememberAnVeMapProperties(isMyLocationEnabled: Boolean = false): MapProperties {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val styleOptions = remember(context, isDark) {
        runCatching {
            MapStyleOptions.loadRawResourceStyle(
                context,
                if (isDark) R.raw.map_style_dark else R.raw.map_style_light,
            )
        }.getOrNull()
    }
    return remember(isMyLocationEnabled, styleOptions) {
        MapProperties(
            isBuildingEnabled = false,
            isMyLocationEnabled = isMyLocationEnabled,
            mapStyleOptions = styleOptions,
        )
    }
}

@Composable
fun rememberPlaceMarkerIcon(name: String, isDark: Boolean = isSystemInDarkTheme()): BitmapDescriptor {
    val density = LocalDensity.current.density
    return remember(name, isDark, density) { placeMarkerIcon(name, isDark, density) }
}

private fun placeMarkerIcon(name: String, isDark: Boolean, density: Float): BitmapDescriptor {
    val label = name.trim().take(16).ifBlank { "Địa điểm" }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDark) MAP_LABEL_TEXT_DARK else MAP_LABEL_TEXT_LIGHT
        textSize = 11f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textWidth = textPaint.measureText(label)
    val dotSize = 8f * density
    val gap = 5f * density
    val padH = 9f * density
    val heightPx = (24f * density).roundToInt().coerceAtLeast(1)
    val widthPx = (padH * 2f + dotSize + gap + textWidth).roundToInt().coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    bitmap.density = Bitmap.DENSITY_NONE
    val canvas = Canvas(bitmap)
    val rect = RectF(1f * density, 1f * density, widthPx - 1f * density, heightPx - 1f * density)
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDark) MAP_LABEL_BG_DARK else MAP_LABEL_BG_LIGHT
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f * density
        color = MAP_FOREST_GREEN
    }
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MAP_FOREST_GREEN }
    canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, bgPaint)
    canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, strokePaint)
    canvas.drawCircle(padH + dotSize / 2f, rect.centerY(), dotSize / 2f, dotPaint)
    val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(label, padH + dotSize + gap, textY, textPaint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

@Composable
fun rememberHistoryPointMarkerIcon(
    label: String,
    isSelected: Boolean,
    isDark: Boolean = isSystemInDarkTheme(),
): Pair<BitmapDescriptor, Float> {
    val density = LocalDensity.current.density
    return remember(label, isSelected, isDark, density) {
        historyPointMarkerIcon(label, isSelected, isDark, density)
    }
}

private fun historyPointMarkerIcon(
    label: String,
    isSelected: Boolean,
    isDark: Boolean,
    density: Float,
): Pair<BitmapDescriptor, Float> {
    val accent = if (isSelected) MAP_FOREST_GREEN else MAP_SAGE_SLATE
    val outerBg = if (isDark) MAP_LABEL_BG_DARK else MAP_CREAM_SAND
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDark) MAP_LABEL_TEXT_DARK else MAP_LABEL_TEXT_LIGHT
        textSize = 10.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val cleanLabel = label.trim()
    val textWidth = textPaint.measureText(cleanLabel)
    val pillPadH = 7f * density
    val pillHeight = 18f * density
    val pillWidth = textWidth + pillPadH * 2f

    val circleSize = (if (isSelected) 22f else 14f) * density
    val circleRadius = circleSize / 2f
    val tipHeight = if (isSelected) 7f * density else 0f
    val pinBodyHeight = circleSize + tipHeight
    val gap = if (cleanLabel.isNotEmpty()) 3f * density else 0f
    val totalWidthPx = maxOf(circleSize + 4f * density, if (cleanLabel.isNotEmpty()) pillWidth + 4f * density else 0f)
        .roundToInt().coerceAtLeast(1)
    val totalHeightPx = (pinBodyHeight + gap + (if (cleanLabel.isNotEmpty()) pillHeight else 0f) + 3f * density)
        .roundToInt().coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(totalWidthPx, totalHeightPx, Bitmap.Config.ARGB_8888)
    bitmap.density = Bitmap.DENSITY_NONE
    val canvas = Canvas(bitmap)
    val cx = totalWidthPx / 2f
    val cy = circleRadius + 2f * density
    val tipY = pinBodyHeight

    val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = outerBg }
    val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    if (isSelected) {
        val tail = Path().apply {
            moveTo(cx - circleRadius * 0.65f, cy + circleRadius * 0.4f)
            lineTo(cx, tipY)
            lineTo(cx + circleRadius * 0.65f, cy + circleRadius * 0.4f)
            close()
        }
        canvas.drawPath(tail, outerPaint)
    }
    canvas.drawCircle(cx, cy, circleRadius + 2f * density, outerPaint)
    canvas.drawCircle(cx, cy, circleRadius, accentPaint)
    canvas.drawCircle(cx, cy, circleRadius * 0.42f, outerPaint)

    if (cleanLabel.isNotEmpty()) {
        val pillTop = tipY + gap
        val rect = RectF(cx - pillWidth / 2f, pillTop, cx + pillWidth / 2f, pillTop + pillHeight)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDark) MAP_LABEL_BG_DARK else MAP_LABEL_BG_LIGHT
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
            color = accent
        }
        canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, bgPaint)
        canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, strokePaint)
        val labelY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(cleanLabel, cx - textWidth / 2f, labelY, textPaint)
    }

    val anchorY = ((if (isSelected) tipY else cy) / totalHeightPx.toFloat()).coerceIn(0.1f, 0.9f)
    return BitmapDescriptorFactory.fromBitmap(bitmap) to anchorY
}

suspend fun fitCameraToPoints(
    camera: CameraPositionState,
    points: List<LatLng>,
    paddingPx: Int = 88,
    singleZoom: Float = 15f,
    animate: Boolean = false,
) {
    val distinct = points.distinct()
    if (distinct.isEmpty()) return
    val update = if (distinct.size == 1) {
        CameraUpdateFactory.newLatLngZoom(distinct.single(), singleZoom)
    } else {
        val bounds = LatLngBounds.builder().apply { distinct.forEach(::include) }.build()
        CameraUpdateFactory.newLatLngBounds(bounds, paddingPx)
    }
    if (animate) camera.animate(update) else camera.move(update)
}

/**
 * Compact floating glass pill used over embedded or full-screen maps for
 * gesture locking, route fitting and fullscreen expansion.
 */
@Composable
fun MapOverlayPillButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) colors.primaryContainer.copy(alpha = 0.95f) else stitch.cardTactile.copy(alpha = 0.92f),
        contentColor = if (active) colors.onPrimaryContainer else colors.onSurface,
        border = BorderStroke(1.dp, if (active) colors.primary else stitch.hairline),
        shadowElevation = 1.dp,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

private const val MAP_FOREST_GREEN = 0xFF3D602F.toInt()
private const val MAP_SAGE_SLATE = 0xFF695B60.toInt()
private const val MAP_CREAM_SAND = 0xFFFDF9F2.toInt()
private const val MAP_LABEL_BG_LIGHT = 0xFFFEFAF3.toInt()
private const val MAP_LABEL_TEXT_LIGHT = 0xFF1C1C18.toInt()
private const val MAP_LABEL_BG_DARK = 0xFF21211C.toInt()
private const val MAP_LABEL_TEXT_DARK = 0xFFE6E2DB.toInt()
private const val NEAR_SAVED_PLACE_MAX_METERS = 2_000.0

data class PlaceMatch(
    val place: PlaceDto,
    val distanceMeters: Double,
    val isInside: Boolean,
)

/**
 * Matches a coordinate against saved/shared family places privately on-device.
 * Inside-radius matches take priority (smallest zone wins on overlap); otherwise
 * returns the nearest place within 2 km.
 */
fun findMatchingPlace(latitude: Double?, longitude: Double?, places: List<PlaceDto>): PlaceMatch? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    if (places.isEmpty()) return null

    val withDist = places.map { place ->
        place to distanceMeters(lat, lon, place.latitude, place.longitude)
    }
    val inside = withDist
        .filter { (place, dist) -> dist <= place.radiusMeters }
        .minWithOrNull(compareBy<Pair<PlaceDto, Double>> { it.first.radiusMeters }.thenBy { it.second })
    if (inside != null) {
        return PlaceMatch(place = inside.first, distanceMeters = inside.second, isInside = true)
    }
    val nearest = withDist.minByOrNull { it.second } ?: return null
    return if (nearest.second <= NEAR_SAVED_PLACE_MAX_METERS) {
        PlaceMatch(place = nearest.first, distanceMeters = nearest.second, isInside = false)
    } else {
        null
    }
}
