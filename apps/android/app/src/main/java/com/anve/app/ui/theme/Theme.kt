package com.anve.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

internal val LightColors = lightColorScheme(
    primary = Teal700,
    onPrimary = Sand50,
    primaryContainer = Teal100,
    onPrimaryContainer = Teal900,
    secondary = Amber600,
    onSecondary = Ink900,
    secondaryContainer = Amber300,
    onSecondaryContainer = Ink900,
    background = Sand50,
    onBackground = Ink900,
    surface = Sand50,
    onSurface = Ink900,
    surfaceDim = Sand200,
    surfaceBright = Sand50,
    surfaceContainerLowest = Sand50,
    surfaceContainerLow = Sand50,
    surfaceContainer = Sand200,
    surfaceContainerHigh = Sand200,
    surfaceContainerHighest = Sand200,
    surfaceVariant = Sand200,
    onSurfaceVariant = Ink600,
)

internal val DarkColors = darkColorScheme(
    primary = Teal300,
    onPrimary = Teal900,
    primaryContainer = Teal700,
    onPrimaryContainer = Teal100,
    secondary = Amber300,
    onSecondary = Ink900,
    secondaryContainer = Amber800,
    onSecondaryContainer = Sand50,
    background = Ink900,
    onBackground = Sand50,
    surface = Ink900,
    onSurface = Sand50,
    surfaceDim = Ink900,
    surfaceBright = Ink700,
    surfaceContainerLowest = Ink900,
    surfaceContainerLow = Ink800,
    surfaceContainer = Ink800,
    surfaceContainerHigh = Ink700,
    surfaceContainerHighest = Ink600,
    surfaceVariant = Ink600,
    onSurfaceVariant = Sand200,
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
    emergency = Amber600,
    onEmergency = Ink900,
    emergencyContainer = Amber300,
    onEmergencyContainer = Ink900,
)

internal val DarkEmergencyColors = EmergencyColors(
    emergency = Amber300,
    onEmergency = Ink900,
    emergencyContainer = Amber800,
    onEmergencyContainer = Sand50,
)

internal val LocalEmergencyColors = staticCompositionLocalOf { LightEmergencyColors }

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

    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(LocalEmergencyColors provides emergencyColors, content = content)
    }
}
