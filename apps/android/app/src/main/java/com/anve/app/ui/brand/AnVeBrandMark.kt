package com.anve.app.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.anve.app.R

@Composable
fun AnVeBrandMark(modifier: Modifier = Modifier) {
    val resource = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) R.drawable.anve_mark_dark else R.drawable.anve_mark_light
    Image(
        painter = painterResource(resource),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
