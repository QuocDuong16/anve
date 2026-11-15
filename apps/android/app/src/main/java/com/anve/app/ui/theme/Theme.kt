package com.anve.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal val LightColors = lightColorScheme(
    primary = ForestPrimary,
    onPrimary = Color.White,
    primaryContainer = ForestPrimaryContainer,
    onPrimaryContainer = ForestOnPrimaryContainer,
    secondary = BlushSecondary,
    onSecondary = Color.White,
    secondaryContainer = BlushSecondaryContainer,
    onSecondaryContainer = BlushOnSecondaryContainer,
    tertiary = WarmAmber,
    onTertiary = Color.White,
    tertiaryContainer = WarmAmberContainer,
    onTertiaryContainer = Color(0xFF2B1700),
    background = CreamBackground,
    onBackground = InkPrimary,
    surface = CreamBackground,
    onSurface = InkPrimary,
    surfaceDim = CreamSurfaceHighest,
    surfaceBright = CreamBackground,
    surfaceContainerLowest = CreamSurfaceLowest,
    surfaceContainerLow = CreamSurfaceLow,
    surfaceContainer = CreamSurface,
    surfaceContainerHigh = CreamSurfaceHigh,
    surfaceContainerHighest = CreamSurfaceHighest,
    surfaceVariant = CreamSurfaceHighest,
    onSurfaceVariant = OliveSurfaceVariant,
    outline = SageOutline,
    outlineVariant = SageOutlineVariant,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorRedContainer,
    onErrorContainer = ErrorOnRedContainer,
)

internal val DarkColors = darkColorScheme(
    primary = ForestLight,
    onPrimary = ForestDarkOnPrimary,
    primaryContainer = ForestDarkContainer,
    onPrimaryContainer = ForestDarkOnContainer,
    secondary = BlushLight,
    onSecondary = BlushDarkOnSecondary,
    secondaryContainer = BlushDarkContainer,
    onSecondaryContainer = BlushSecondaryContainer,
    tertiary = WarmAmberLight,
    onTertiary = Color(0xFF472A00),
    tertiaryContainer = WarmAmberDark,
    onTertiaryContainer = WarmAmberContainer,
    background = InkDarkBackground,
    onBackground = CreamSurfaceHighest,
    surface = InkDarkBackground,
    onSurface = CreamSurfaceHighest,
    surfaceDim = InkDarkBackground,
    surfaceBright = InkDarkContainerHighest,
    surfaceContainerLowest = InkDarkLowest,
    surfaceContainerLow = InkPrimary,
    surfaceContainer = InkDarkContainer,
    surfaceContainerHigh = InkDarkContainerHigh,
    surfaceContainerHighest = InkDarkContainerHighest,
    surfaceVariant = OliveSurfaceVariant,
    onSurfaceVariant = SageOutlineVariant,
    outline = SageOutlineDark,
    outlineVariant = OliveSurfaceVariant,
    error = ErrorRedDark,
    onError = ErrorOnRedDark,
    errorContainer = ErrorRedDarkContainer,
    onErrorContainer = ErrorOnRedDarkContainer,
)

/**
 * Emergency marks urgent safety actions (SOS). It is deliberately separate
 * from `error`: amber urgency asks the user to act now, while `error` stays
 * reserved for failures. Both pairs are drawn from the brand amber ramp and
 * are covered by ThemeContrastTest at body-text contrast.
 */
data class EmergencyColors(
    val emergency: Color,
    val onEmergency: Color,
    val emergencyContainer: Color,
    val onEmergencyContainer: Color,
)

internal val LightEmergencyColors = EmergencyColors(
    emergency = EmergencyRose,
    onEmergency = Color.White,
    emergencyContainer = EmergencyRoseContainer,
    onEmergencyContainer = EmergencyOnRoseContainer,
)

internal val DarkEmergencyColors = EmergencyColors(
    emergency = EmergencyRoseDark,
    onEmergency = EmergencyOnRoseDark,
    emergencyContainer = EmergencyRoseDarkContainer,
    onEmergencyContainer = EmergencyOnRoseDarkContainer,
)

internal val LocalEmergencyColors = staticCompositionLocalOf { LightEmergencyColors }

/**
 * Extended Stitch "Warm Tactile Minimalism" design tokens used for fixed
 * pills, live pulse dots, transit/active status badges, and cream tactile cards.
 */
data class StitchColors(
    val primaryFixed: Color,
    val onPrimaryFixed: Color,
    val primaryFixedDim: Color,
    val secondaryFixed: Color,
    val onSecondaryFixed: Color,
    val secondaryFixedDim: Color,
    val livePulse: Color,
    val liveContainer: Color,
    val onLiveContainer: Color,
    val transitContainer: Color,
    val onTransitContainer: Color,
    val cardTactile: Color,
    val onCardTactile: Color,
    val hairline: Color,
)

internal val LightStitchColors = StitchColors(
    primaryFixed = PrimaryFixed,
    onPrimaryFixed = OnPrimaryFixed,
    primaryFixedDim = PrimaryFixedDim,
    secondaryFixed = SecondaryFixed,
    onSecondaryFixed = OnSecondaryFixed,
    secondaryFixedDim = SecondaryFixedDim,
    livePulse = StitchLiveGreen,
    liveContainer = StitchLiveGreenSoft,
    onLiveContainer = StitchOnLiveGreenSoft,
    transitContainer = StitchTransitAmberContainer,
    onTransitContainer = StitchOnTransitAmberContainer,
    cardTactile = CreamCardTactile,
    onCardTactile = InkPrimary,
    hairline = CreamHairline,
)

internal val DarkStitchColors = StitchColors(
    primaryFixed = ForestDarkContainer,
    onPrimaryFixed = PrimaryFixed,
    primaryFixedDim = ForestLight,
    secondaryFixed = BlushDarkContainer,
    onSecondaryFixed = SecondaryFixed,
    secondaryFixedDim = BlushLight,
    livePulse = StitchLiveGreenDark,
    liveContainer = StitchLiveGreenDarkContainer,
    onLiveContainer = StitchOnLiveGreenDarkContainer,
    transitContainer = StitchTransitAmberDarkContainer,
    onTransitContainer = StitchOnTransitAmberDarkContainer,
    cardTactile = DarkCardTactile,
    onCardTactile = CreamSurfaceHighest,
    hairline = DarkHairline,
)

internal val LocalStitchColors = staticCompositionLocalOf { LightStitchColors }

internal val AnVeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AnVeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You colours are opt-in: the brand palette is the default so
    // every device shows the same AnVề identity.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    // Emergency keeps the brand amber even under dynamic color: an urgent
    // safety action must stay recognizable regardless of the wallpaper.
    val emergencyColors = if (darkTheme) DarkEmergencyColors else LightEmergencyColors
    val stitchColors = if (darkTheme) DarkStitchColors else LightStitchColors

    MaterialTheme(colorScheme = colorScheme, typography = AnVeTypography, shapes = AnVeShapes) {
        CompositionLocalProvider(
            LocalEmergencyColors provides emergencyColors,
            LocalStitchColors provides stitchColors,
            content = content,
        )
    }
}
