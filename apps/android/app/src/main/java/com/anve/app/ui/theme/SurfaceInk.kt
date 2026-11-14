package com.anve.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * True when the active scheme draws on a dark surface. Ad-hoc
 * `surface.luminance() < 0.5f` checks used to be scattered across screens;
 * this is the single place that decides, and it reads the scheme's own color
 * roles so the answer follows the active scheme (brand static or Material
 * You) instead of a guessed dark-mode flag.
 */
@Composable
fun isDarkSurface(): Boolean {
    val colors = MaterialTheme.colorScheme
    return remember(colors) { colors.surface.luminance() < 0.5f }
}

/**
 * Ink for emphasis actions drawn directly on the current surface: pale
 * `onSurface` on a dark surface, saturated `primary` on a light one. The
 * Paired Roles Rule still governs container pairs; this helper only names
 * the foreground that stays readable on the raw surface.
 */
@Composable
fun rememberInkOnSurface(): Color =
    if (isDarkSurface()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
