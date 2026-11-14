package com.anve.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {

    @Test
    fun `light semantic color pairs meet body text contrast`() {
        assertSchemeContrast(LightColors)
    }

    @Test
    fun `dark semantic color pairs meet body text contrast`() {
        assertSchemeContrast(DarkColors)
    }

    @Test
    fun `light emergency color pairs meet body text contrast`() {
        assertEmergencyContrast(LightEmergencyColors, LightColors.error)
    }

    @Test
    fun `dark emergency color pairs meet body text contrast`() {
        assertEmergencyContrast(DarkEmergencyColors, DarkColors.error)
    }

    private fun assertSchemeContrast(scheme: androidx.compose.material3.ColorScheme) {
        listOf(
            "primary" to (scheme.primary to scheme.onPrimary),
            "primaryContainer" to (scheme.primaryContainer to scheme.onPrimaryContainer),
            "secondary" to (scheme.secondary to scheme.onSecondary),
            "secondaryContainer" to (scheme.secondaryContainer to scheme.onSecondaryContainer),
            "surface" to (scheme.surface to scheme.onSurface),
            "surfaceContainerLow" to (scheme.surfaceContainerLow to scheme.onSurface),
            "surfaceContainerHigh" to (scheme.surfaceContainerHigh to scheme.onSurface),
            "surfaceContainerHighest" to (scheme.surfaceContainerHighest to scheme.onSurface),
            "surfaceVariant" to (scheme.surfaceVariant to scheme.onSurfaceVariant),
        ).forEach { (role, colors) ->
            val ratio = contrastRatio(colors.first, colors.second)
            assertTrue("$role contrast was $ratio:1", ratio >= 4.5)
        }
    }

    // Emergency is an urgent-but-not-error role: its pairs must stay readable
    // like the scheme pairs, and it must never collapse into the error role.
    private fun assertEmergencyContrast(colors: EmergencyColors, error: Color) {
        listOf(
            "emergency" to (colors.emergency to colors.onEmergency),
            "emergencyContainer" to (colors.emergencyContainer to colors.onEmergencyContainer),
        ).forEach { (role, pair) ->
            val ratio = contrastRatio(pair.first, pair.second)
            assertTrue("$role contrast was $ratio:1", ratio >= 4.5)
        }
        assertTrue("emergency must not reuse the error role", colors.emergency != error)
    }

    private fun contrastRatio(background: Color, foreground: Color): Double {
        val (lighter, darker) = listOf(background.luminance(), foreground.luminance()).sortedDescending()
        return (lighter + 0.05) / (darker + 0.05)
    }
}
