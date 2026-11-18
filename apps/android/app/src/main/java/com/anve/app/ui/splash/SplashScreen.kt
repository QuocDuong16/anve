package com.anve.app.ui.splash

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anve.app.BuildConfig
import com.anve.app.R
import com.anve.app.ui.brand.AnVeBrandMark
import com.anve.app.ui.brand.AnVeFamilySceneIllustration
import com.anve.app.ui.common.anveContentWidth
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.LocalStitchColors

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
    val stitch = LocalStitchColors.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .anveContentWidth()
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
        ) {
            AnVeBrandMark(
                modifier = Modifier
                    .size(124.dp)
                    .clip(CircleShape)
                    .background(stitch.secondaryFixed)
                    .border(BorderStroke(1.5.dp, stitch.hairline), CircleShape)
                    .padding(14.dp),
            )
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
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
            AnVeFamilySceneIllustration(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
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

@Preview(name = "1. Standard Light", showBackground = true)
@Composable
private fun SplashScreenPreview() {
    AnVeTheme {
        SplashScreen(version = "0.1.0")
    }
}

@Preview(name = "2. Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SplashScreenDarkPreview() {
    AnVeTheme(darkTheme = true) {
        SplashScreen(version = "0.1.0")
    }
}

@Preview(name = "3. Elderly 200% Font", fontScale = 2.0f, showBackground = true)
@Composable
private fun SplashScreenElderlyFontPreview() {
    AnVeTheme {
        SplashScreen(version = "0.1.0")
    }
}
