package com.anve.app.ui.splash

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anve.app.BuildConfig
import com.anve.app.R
import com.anve.app.ui.brand.AnVeBrandMark
import com.anve.app.ui.brand.AnVeFamilySceneIllustration
import com.anve.app.ui.theme.AnVeTheme

/**
 * The AnVề splash screen shown while the app bootstraps.
 *
 * @param version the running build version. Rendered only in debug builds so
 * developers can confirm which build they hold; production users see the
 * brand surface without the developer label.
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
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(28.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = colors.primaryContainer.copy(alpha = 0.45f),
                border = BorderStroke(1.5.dp, colors.primary.copy(alpha = 0.2f)),
            ) {
                AnVeBrandMark(Modifier.padding(14.dp).size(104.dp))
            }
            Text(
                text = SplashCopy.displayName,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = colors.primary,
                modifier = Modifier.padding(top = 20.dp),
            )
            // The tagline reads from strings.xml because SignInScreen renders
            // the same line; keeping it in a resource prevents the two
            // surfaces from drifting apart.
            Text(
                text = stringResource(R.string.splash_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = colors.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AnVeFamilySceneIllustration(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(116.dp)
                        .padding(6.dp),
                )
            }
            // The version label is a developer aid, not brand copy: shipping it
            // to production reads as a debug screen left on. Gate it on the
            // build type instead of dropping the parameter so debug installs
            // keep confirming which build they run.
            if (BuildConfig.DEBUG) {
                Text(
                    text = SplashCopy.versionLabel(version),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 28.dp),
                )
            }
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
