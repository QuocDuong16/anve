package com.anve.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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

/**
 * Aligns system status bar icon contrast with a branded TopAppBar background
 * and restores the prior appearance when leaving the screen.
 */
@Composable
fun SystemStatusBarIcons(darkIcons: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(context, view, darkIcons) {
        val controller = context.findHostActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = darkIcons
        onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
