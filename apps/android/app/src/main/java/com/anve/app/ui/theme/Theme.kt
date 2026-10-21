package com.anve.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Teal700,
    onPrimary = Sand50,
    primaryContainer = Teal100,
    onPrimaryContainer = Teal900,
    secondary = Amber600,
    onSecondary = Sand50,
    secondaryContainer = Amber300,
    onSecondaryContainer = Ink900,
    background = Sand50,
    onBackground = Ink900,
    surface = Sand50,
    onSurface = Ink900,
    surfaceVariant = Sand200,
    onSurfaceVariant = Ink600,
)

private val DarkColors = darkColorScheme(
    primary = Teal400,
    onPrimary = Teal900,
    primaryContainer = Teal700,
    onPrimaryContainer = Teal100,
    secondary = Amber300,
    onSecondary = Ink900,
    secondaryContainer = Amber600,
    onSecondaryContainer = Sand50,
    background = Ink900,
    onBackground = Sand50,
    surface = Ink900,
    onSurface = Sand50,
    surfaceVariant = Ink600,
    onSurfaceVariant = Sand200,
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

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
