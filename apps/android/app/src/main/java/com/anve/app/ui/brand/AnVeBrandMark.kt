package com.anve.app.ui.brand

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Primary brand emblem for AnVề. Renders the warm illustrated home-and-path mark
 * defined in [AnVeHomeEmblem] so splash, sign-in, and shell headers share a cohesive
 * theme-aware identity across light and dark modes.
 */
@Composable
fun AnVeBrandMark(modifier: Modifier = Modifier) {
    AnVeHomeEmblem(modifier = modifier)
}
