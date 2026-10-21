package com.anve.app.ui

import androidx.compose.runtime.Composable
import com.anve.app.BuildConfig
import com.anve.app.ui.splash.SplashScreen

/**
 * Root composable of the AnVề app.
 *
 * M0 ships the startup screen only: it proves the Compose toolchain, the
 * theme and the app entry point all work end to end. Navigation and the real
 * home screen arrive in a later milestone.
 */
@Composable
fun AnVeApp() {
    SplashScreen(version = BuildConfig.VERSION_NAME)
}
