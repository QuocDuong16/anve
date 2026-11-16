package com.anve.app.ui.brand

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anve.app.R
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.BlushSecondary
import com.anve.app.ui.theme.BlushSecondaryContainer
import com.anve.app.ui.theme.CreamBackground
import com.anve.app.ui.theme.CreamSurfaceHigh
import com.anve.app.ui.theme.CreamSurfaceHighest
import com.anve.app.ui.theme.EmergencyRose
import com.anve.app.ui.theme.EmergencyRoseDark
import com.anve.app.ui.theme.ForestDarkContainer
import com.anve.app.ui.theme.ForestLight
import com.anve.app.ui.theme.ForestPrimary
import com.anve.app.ui.theme.ForestPrimaryContainer
import com.anve.app.ui.theme.InkDarkBackground
import com.anve.app.ui.theme.InkDarkContainer
import com.anve.app.ui.theme.InkPrimary
import com.anve.app.ui.theme.LocalStitchColors
import com.anve.app.ui.theme.WarmAmber
import com.anve.app.ui.theme.WarmAmberContainer
import com.anve.app.ui.theme.WarmAmberDark
import com.anve.app.ui.theme.WarmAmberLight
import com.anve.app.ui.theme.isDarkSurface

private data class IllustrationPalette(
    val sky: Color,
    val skySecondary: Color,
    val meadow: Color,
    val meadowDeep: Color,
    val pathSurface: Color,
    val wall: Color,
    val roof: Color,
    val door: Color,
    val windowGlow: Color,
    val windowMuted: Color,
    val sun: Color,
    val treeFoliage: Color,
    val treeFoliageMuted: Color,
    val lineAccent: Color,
    val pinFill: Color,
    val zoneFill: Color,
    val personOneShirt: Color,
    val personTwoShirt: Color,
    val childShirt: Color,
    val skin: Color,
    val hair: Color,
)

@Composable
private fun rememberIllustrationPalette(): IllustrationPalette {
    val isDark = isDarkSurface()
    return if (isDark) {
        IllustrationPalette(
            sky = InkDarkContainer,
            skySecondary = ForestDarkContainer,
            meadow = Color(0xFF233A1B),
            meadowDeep = Color(0xFF192B13),
            pathSurface = WarmAmberDark,
            wall = CreamSurfaceHighest,
            roof = Color(0xFFB45A46),
            door = WarmAmber,
            windowGlow = WarmAmberLight,
            windowMuted = ForestDarkContainer,
            sun = WarmAmberLight,
            treeFoliage = ForestPrimaryContainer,
            treeFoliageMuted = ForestDarkContainer,
            lineAccent = ForestLight,
            pinFill = ForestLight,
            zoneFill = ForestLight.copy(alpha = 0.24f),
            personOneShirt = ForestLight,
            personTwoShirt = EmergencyRoseDark,
            childShirt = WarmAmberLight,
            skin = Color(0xFFEAC1A3),
            hair = InkDarkBackground,
        )
    } else {
        IllustrationPalette(
            sky = Color(0xFFE6EFE0),
            skySecondary = BlushSecondaryContainer,
            meadow = ForestPrimaryContainer.copy(alpha = 0.78f),
            meadowDeep = ForestPrimary,
            pathSurface = WarmAmberContainer.copy(alpha = 0.85f),
            wall = CreamBackground,
            roof = BlushSecondary,
            door = WarmAmber,
            windowGlow = Color(0xFFFFD885),
            windowMuted = CreamSurfaceHigh,
            sun = WarmAmber,
            treeFoliage = ForestPrimaryContainer,
            treeFoliageMuted = ForestLight.copy(alpha = 0.55f),
            lineAccent = ForestPrimary,
            pinFill = ForestPrimary,
            zoneFill = ForestPrimaryContainer.copy(alpha = 0.20f),
            personOneShirt = ForestPrimary,
            personTwoShirt = EmergencyRose,
            childShirt = WarmAmber,
            skin = Color(0xFFF0C7AA),
            hair = InkPrimary,
        )
    }
}

/**
 * Circular home emblem representing "An Về": a warm lit cottage framed by leafy garden
 * and a welcoming path leading up to the doorway.
 */
@Composable
fun AnVeHomeEmblem(modifier: Modifier = Modifier) {
    val palette = rememberIllustrationPalette()
    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val radius = diameter / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(color = palette.skySecondary, radius = radius, center = center)
        drawCircle(
            color = palette.sun.copy(alpha = 0.25f),
            radius = radius * 0.34f,
            center = Offset(center.x + radius * 0.42f, center.y - radius * 0.42f),
        )
        drawCircle(
            color = palette.sun,
            radius = radius * 0.14f,
            center = Offset(center.x + radius * 0.44f, center.y - radius * 0.44f),
        )

        val gardenPath = Path().apply {
            val left = center.x - radius * 0.92f
            val right = center.x + radius * 0.92f
            val top = center.y + radius * 0.28f
            val bottom = center.y + radius * 0.96f
            moveTo(left, top + radius * 0.12f)
            cubicTo(
                center.x - radius * 0.25f,
                top - radius * 0.12f,
                center.x + radius * 0.25f,
                top - radius * 0.08f,
                right,
                top + radius * 0.12f,
            )
            cubicTo(
                center.x + radius * 0.72f,
                bottom,
                center.x - radius * 0.72f,
                bottom,
                left,
                top + radius * 0.12f,
            )
            close()
        }
        drawPath(gardenPath, color = palette.meadow)

        drawWindingPath(
            startX = center.x - radius * 0.02f,
            startY = center.y + radius * 0.34f,
            endX = center.x - radius * 0.12f,
            endY = center.y + radius * 0.86f,
            startWidth = radius * 0.16f,
            endWidth = radius * 0.46f,
            color = palette.pathSurface,
        )

        drawCozyHouse(
            left = center.x - radius * 0.46f,
            top = center.y - radius * 0.42f,
            width = radius * 0.92f,
            height = radius * 0.78f,
            palette = palette,
            lit = true,
        )

        drawCircle(
            color = palette.lineAccent.copy(alpha = 0.22f),
            radius = radius - 1.5f,
            center = center,
            style = Stroke(width = 3f),
        )
    }
}

/**
 * Renders an editorial WebP scene illustration framed consistently for Light and Dark surfaces.
 */
@Suppress("UnusedBoxWithConstraintsScope")
@Composable
private fun AnVeRasterScene(
    @DrawableRes drawableRes: Int,
    backdropColor: Color,
    imageAspectRatio: Float,
    modifier: Modifier = Modifier,
    circular: Boolean = false,
    dimmed: Boolean = false,
    featherHorizontalEdges: Boolean = false,
) {
    val isDark = isDarkSurface()
    val stitch = LocalStitchColors.current
    val clipShape = if (circular) CircleShape else RoundedCornerShape(18.dp)
    val filter = remember(dimmed, isDark) {
        when {
            dimmed -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.35f) })
            isDark -> ColorFilter.colorMatrix(
                ColorMatrix().apply {
                    // Gently tone paper-cream artwork so prints integrate naturally on dark cards.
                    setToScale(0.92f, 0.90f, 0.87f, 1f)
                },
            )
            else -> null
        }
    }
    val darkSurfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaceColor = remember(backdropColor, isDark, darkSurfaceColor) {
        if (isDark) darkSurfaceColor else backdropColor
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(clipShape)
            .background(surfaceColor)
            .then(
                if (isDark) {
                    Modifier.border(1.dp, stitch.hairline, clipShape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val boxAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else imageAspectRatio
        val useCrop = circular || (boxAspect <= imageAspectRatio * 1.45f && boxAspect >= imageAspectRatio * 0.68f)

        Image(
            painter = painterResource(drawableRes),
            contentDescription = null,
            contentScale = if (useCrop) ContentScale.Crop else ContentScale.Fit,
            alpha = if (dimmed) 0.72f else 1f,
            colorFilter = filter,
            modifier = Modifier.fillMaxSize(),
        )

        if (!useCrop && featherHorizontalEdges && !isDark) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(0.28f)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(backdropColor, Color.Transparent),
                            ),
                        ),
                )
                Spacer(modifier = Modifier.weight(0.44f))
                Box(
                    modifier = Modifier
                        .weight(0.28f)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, backdropColor),
                            ),
                        ),
                )
            }
        }
    }
}

/**
 * Banner illustration for the Family Circle header, splash, sign-in, and onboarding empty state,
 * depicting a sunlit home and family walking home together.
 */
@Composable
fun AnVeFamilySceneIllustration(modifier: Modifier = Modifier) {
    AnVeRasterScene(
        drawableRes = R.drawable.hero_family_welcome,
        backdropColor = Color(0xFFFDF9ED),
        imageAspectRatio = 1f,
        featherHorizontalEdges = true,
        modifier = modifier,
    )
}

/**
 * Editorial illustration for the Sharing Hub hero card, softly desaturated when sharing is paused.
 */
@Composable
fun AnVeSharingHeroIllustration(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    AnVeRasterScene(
        drawableRes = R.drawable.scene_sharing_hub,
        backdropColor = Color(0xFFFDF7E9),
        imageAspectRatio = 16f / 9f,
        dimmed = !active,
        featherHorizontalEdges = true,
        modifier = modifier,
    )
}

/**
 * Editorial valley route illustration for the Safe Journey ("Hành trình An Về") cards and active state.
 */
@Composable
fun AnVeJourneySceneIllustration(modifier: Modifier = Modifier) {
    AnVeRasterScene(
        drawableRes = R.drawable.scene_journey_route,
        backdropColor = Color(0xFFFBF5E7),
        imageAspectRatio = 16f / 9f,
        featherHorizontalEdges = true,
        modifier = modifier,
    )
}

/**
 * Editorial safe-zone landmarks illustration for the Places empty state and setup cards.
 */
@Composable
fun AnVePlacesSceneIllustration(modifier: Modifier = Modifier) {
    AnVeRasterScene(
        drawableRes = R.drawable.scene_safe_places_empty,
        backdropColor = Color(0xFFFDF6EE),
        imageAspectRatio = 4f / 3f,
        modifier = modifier,
    )
}

/**
 * Calm journal illustration for an empty History day showing a breadcrumb trail and pocket watch.
 */
@Composable
fun AnVeHistorySceneIllustration(modifier: Modifier = Modifier) {
    AnVeRasterScene(
        drawableRes = R.drawable.scene_history_timeline_empty,
        backdropColor = Color(0xFFFBF7EB),
        imageAspectRatio = 4f / 3f,
        featherHorizontalEdges = true,
        modifier = modifier,
    )
}

private fun DrawScope.drawCozyHouse(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    palette: IllustrationPalette,
    lit: Boolean,
) {
    val wallTop = top + height * 0.38f
    val wallHeight = height * 0.62f
    val wallLeft = left + width * 0.12f
    val wallWidth = width * 0.76f

    // Chimney
    drawRoundRect(
        color = palette.roof.copy(alpha = 0.85f),
        topLeft = Offset(wallLeft + wallWidth * 0.12f, top + height * 0.06f),
        size = Size(width * 0.14f, height * 0.30f),
        cornerRadius = CornerRadius(4f, 4f),
    )

    // Wall
    drawRoundRect(
        color = palette.wall,
        topLeft = Offset(wallLeft, wallTop),
        size = Size(wallWidth, wallHeight),
        cornerRadius = CornerRadius(width * 0.06f, width * 0.06f),
    )

    // Roof
    val roof = Path().apply {
        moveTo(left + width * 0.50f, top)
        lineTo(left + width, wallTop + height * 0.08f)
        lineTo(left, wallTop + height * 0.08f)
        close()
    }
    drawPath(roof, color = palette.roof)

    // Door
    val doorW = wallWidth * 0.26f
    val doorH = wallHeight * 0.56f
    drawRoundRect(
        color = palette.door,
        topLeft = Offset(wallLeft + wallWidth * 0.58f, wallTop + wallHeight - doorH),
        size = Size(doorW, doorH),
        cornerRadius = CornerRadius(doorW * 0.45f, doorW * 0.45f),
    )

    // Window
    val winSize = wallWidth * 0.26f
    drawRoundRect(
        color = if (lit) palette.windowGlow else palette.windowMuted,
        topLeft = Offset(wallLeft + wallWidth * 0.16f, wallTop + wallHeight * 0.26f),
        size = Size(winSize, winSize),
        cornerRadius = CornerRadius(winSize * 0.22f, winSize * 0.22f),
    )
}

private fun DrawScope.drawWindingPath(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    startWidth: Float,
    endWidth: Float,
    color: Color,
) {
    val path = Path().apply {
        moveTo(startX - startWidth / 2f, startY)
        cubicTo(
            startX - startWidth,
            (startY + endY) * 0.5f,
            endX - endWidth * 0.35f,
            (startY + endY) * 0.72f,
            endX - endWidth / 2f,
            endY,
        )
        lineTo(endX + endWidth / 2f, endY)
        cubicTo(
            endX + endWidth * 0.32f,
            (startY + endY) * 0.70f,
            startX + startWidth * 0.65f,
            (startY + endY) * 0.48f,
            startX + startWidth / 2f,
            startY,
        )
        close()
    }
    drawPath(path, color = color)
}

private fun DrawScope.drawSoftTree(
    center: Offset,
    radius: Float,
    foliage: Color,
    trunk: Color,
) {
    drawRoundRect(
        color = trunk,
        topLeft = Offset(center.x - radius * 0.14f, center.y + radius * 0.25f),
        size = Size(radius * 0.28f, radius * 0.9f),
        cornerRadius = CornerRadius(4f, 4f),
    )
    drawCircle(color = foliage, radius = radius * 0.75f, center = center)
    drawCircle(
        color = foliage,
        radius = radius * 0.55f,
        center = Offset(center.x - radius * 0.42f, center.y + radius * 0.16f),
    )
    drawCircle(
        color = foliage,
        radius = radius * 0.55f,
        center = Offset(center.x + radius * 0.42f, center.y + radius * 0.16f),
    )
}

private fun DrawScope.drawPerson(
    centerX: Float,
    baseY: Float,
    height: Float,
    shirtColor: Color,
    skinColor: Color,
    hairColor: Color,
    longHair: Boolean,
) {
    val headRadius = height * 0.20f
    val headCenter = Offset(centerX, baseY - height + headRadius)

    if (longHair) {
        drawRoundRect(
            color = hairColor,
            topLeft = Offset(headCenter.x - headRadius * 1.25f, headCenter.y - headRadius * 1.05f),
            size = Size(headRadius * 2.5f, headRadius * 2.8f),
            cornerRadius = CornerRadius(headRadius, headRadius),
        )
    }

    // Shoulders & torso
    drawRoundRect(
        color = shirtColor,
        topLeft = Offset(centerX - headRadius * 1.8f, headCenter.y + headRadius * 1.05f),
        size = Size(headRadius * 3.6f, height - headRadius * 2.0f),
        cornerRadius = CornerRadius(headRadius * 1.2f, headRadius * 1.2f),
    )

    // Head
    drawCircle(color = skinColor, radius = headRadius, center = headCenter)
    // Hair cap
    drawArc(
        color = hairColor,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(headCenter.x - headRadius, headCenter.y - headRadius * 1.05f),
        size = Size(headRadius * 2f, headRadius * 1.45f),
    )
}

/**
 * Warm protective beacon and family care shield illustration for SOS alerts.
 */
/**
 * Dignified reassuring care beacon and protective shelter scene for the SOS hero card.
 */
@Composable
fun AnVeSosCareIllustration(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val nearSquare = maxHeight > 0.dp && maxWidth <= maxHeight * 1.35f
        AnVeRasterScene(
            drawableRes = R.drawable.scene_sos_care_banner,
            backdropColor = Color(0xFFFCF8EC),
            imageAspectRatio = 5f / 3f,
            circular = nearSquare,
            featherHorizontalEdges = !nearSquare,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Warm profile and privacy shield scene for Account and Sign-In surfaces.
 */
@Composable
fun AnVeAccountShieldIllustration(modifier: Modifier = Modifier) {
    val palette = rememberIllustrationPalette()
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        drawRoundRect(
            color = palette.sky,
            topLeft = Offset.Zero,
            size = Size(w, h),
            cornerRadius = CornerRadius(h * 0.20f, h * 0.20f),
        )
        drawOval(
            color = palette.meadow,
            topLeft = Offset(-w * 0.1f, h * 0.66f),
            size = Size(w * 1.2f, h * 0.6f),
        )
        drawCircle(
            color = palette.sun.copy(alpha = 0.28f),
            radius = h * 0.24f,
            center = Offset(w * 0.76f, h * 0.28f),
        )
        drawCozyHouse(
            left = w * 0.56f,
            top = h * 0.28f,
            width = w * 0.24f,
            height = h * 0.48f,
            palette = palette,
            lit = true,
        )
        drawSoftTree(
            center = Offset(w * 0.18f, h * 0.50f),
            radius = h * 0.18f,
            foliage = palette.treeFoliage,
            trunk = palette.hair,
        )
        // Friendly family trio inside their privacy halo
        drawCircle(
            color = palette.wall.copy(alpha = 0.55f),
            radius = h * 0.28f,
            center = Offset(w * 0.38f, h * 0.64f),
        )
        drawPerson(
            centerX = w * 0.33f,
            baseY = h * 0.84f,
            height = h * 0.36f,
            shirtColor = palette.personOneShirt,
            skinColor = palette.skin,
            hairColor = palette.hair,
            longHair = false,
        )
        drawPerson(
            centerX = w * 0.44f,
            baseY = h * 0.85f,
            height = h * 0.33f,
            shirtColor = palette.childShirt,
            skinColor = palette.skin,
            hairColor = palette.hair,
            longHair = true,
        )
    }
}

/**
 * Friendly home-signal check scene for Diagnostics: calm teal when healthy,
 * warm amber when attention is needed.
 */
@Composable
fun AnVeDiagnosticsIllustration(
    attention: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = rememberIllustrationPalette()
    val signalColor = if (attention) palette.sun else palette.lineAccent
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        drawRoundRect(
            color = if (attention) palette.skySecondary else palette.sky,
            topLeft = Offset.Zero,
            size = Size(w, h),
            cornerRadius = CornerRadius(h * 0.18f, h * 0.18f),
        )
        drawOval(
            color = palette.meadow,
            topLeft = Offset(-w * 0.08f, h * 0.68f),
            size = Size(w * 1.16f, h * 0.58f),
        )
        drawSoftTree(
            center = Offset(w * 0.16f, h * 0.52f),
            radius = h * 0.16f,
            foliage = palette.treeFoliageMuted,
            trunk = palette.hair,
        )
        drawCozyHouse(
            left = w * 0.24f,
            top = h * 0.30f,
            width = w * 0.22f,
            height = h * 0.46f,
            palette = palette,
            lit = true,
        )

        // Signal arc between home and beacon node
        val arcStart = Offset(w * 0.46f, h * 0.46f)
        val arcEnd = Offset(w * 0.78f, h * 0.36f)
        val signalPath = Path().apply {
            moveTo(arcStart.x, arcStart.y)
            quadraticTo(w * 0.62f, h * 0.20f, arcEnd.x, arcEnd.y)
        }
        drawPath(
            path = signalPath,
            color = signalColor,
            style = Stroke(
                width = 4f,
                cap = StrokeCap.Round,
                pathEffect = if (attention) PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f) else null,
            ),
        )

        // Beacon pulse node on the right
        drawCircle(
            color = signalColor.copy(alpha = 0.18f),
            radius = h * 0.22f,
            center = arcEnd,
        )
        drawCircle(
            color = signalColor.copy(alpha = 0.35f),
            radius = h * 0.13f,
            center = arcEnd,
        )
        drawCircle(
            color = signalColor,
            radius = h * 0.06f,
            center = arcEnd,
        )
    }
}

/**
 * Tactile safe-zone radar illustration showing a warm paper neighborhood map,
 * concentric safety rings, and family cottage anchor.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AnVeSafeRadarScene(
    memberCount: Int,
    modifier: Modifier = Modifier,
) {
    AnVeRasterScene(
        drawableRes = R.drawable.scene_map_safe_radar,
        backdropColor = Color(0xFFFCF8EC),
        imageAspectRatio = 16f / 9f,
        featherHorizontalEdges = true,
        modifier = modifier,
    )
}

/**
 * Tactile Stitch framing wrapper for scene illustrations with optional floating corner badge.
 */
@Composable
fun AnVeIllustratedBanner(
    modifier: Modifier = Modifier,
    overlayBadge: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val stitch = LocalStitchColors.current
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = stitch.cardTactile,
        border = BorderStroke(1.dp, stitch.hairline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(6.dp)) {
            content()
            if (overlayBadge != null) {
                overlayBadge()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun IllustrationsLightPreview() {
    AnVeTheme(darkTheme = false) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnVeHomeEmblem(Modifier.size(64.dp))
            AnVeFamilySceneIllustration(Modifier.fillMaxWidth().height(96.dp))
            AnVeSharingHeroIllustration(active = true, modifier = Modifier.fillMaxWidth().height(96.dp))
            AnVeJourneySceneIllustration(Modifier.fillMaxWidth().height(84.dp))
            AnVePlacesSceneIllustration(Modifier.fillMaxWidth().height(84.dp))
            AnVeSafeRadarScene(memberCount = 3, modifier = Modifier.fillMaxWidth().height(96.dp))
            AnVeHistorySceneIllustration(Modifier.fillMaxWidth().height(84.dp))
            AnVeSosCareIllustration(Modifier.fillMaxWidth().height(84.dp))
            AnVeAccountShieldIllustration(Modifier.fillMaxWidth().height(84.dp))
            AnVeDiagnosticsIllustration(attention = false, modifier = Modifier.fillMaxWidth().height(84.dp))
        }
    }
}
