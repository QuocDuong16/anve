package com.anve.app.ui.splash

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anve.app.ui.theme.AnVeTheme

/**
 * The AnVề splash screen shown while the app bootstraps.
 *
 * @param version the running build version, displayed under the wordmark.
 */
@Composable
fun SplashScreen(
    version: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(colors.primary, colors.primaryContainer),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            AnVeMark()
            Text(
                text = SplashCopy.displayName,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 44.sp,
                ),
                color = colors.onPrimary,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = SplashCopy.tagline,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onPrimary.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = SplashCopy.versionLabel(version),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onPrimary.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 40.dp),
            )
        }
    }
}

/**
 * The animated AnVề logo mark: concentric rings that breathe in and out.
 */
@Composable
private fun AnVeMark(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "anve-mark")
    val scale by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "anve-mark-scale",
    )

    val colors = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .size(112.dp)
            .scale(scale)
            .background(colors.onPrimary.copy(alpha = 0.10f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(colors.onPrimary.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(colors.onPrimary, CircleShape),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SplashScreenPreview() {
    AnVeTheme {
        SplashScreen(version = "0.1.0")
    }
}
