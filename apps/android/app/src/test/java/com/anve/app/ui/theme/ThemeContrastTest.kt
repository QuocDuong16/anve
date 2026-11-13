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

    private fun contrastRatio(background: Color, foreground: Color): Double {
        val (lighter, darker) = listOf(background.luminance(), foreground.luminance()).sortedDescending()
        return (lighter + 0.05) / (darker + 0.05)
    }
}
