package com.anve.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.anve.app.AppContainer
import com.anve.app.BuildConfig
import com.anve.app.ui.home.HomeScreen
import com.anve.app.ui.signin.SignInScreen
import com.anve.app.ui.splash.SplashScreen
import kotlinx.coroutines.delay

private object Routes {
    const val SPLASH = "splash"
    const val SIGN_IN = "signin"
    const val HOME = "home"
}

/**
 * Root composable: splash, then the stored session decides between sign-in
 * and home. The splash stays a brief brand moment, not a loading gate —
 * the session read is fast and cached.
 */
@Composable
fun AnVeApp(container: AppContainer) {
    val navController = rememberNavController()
    val session by container.authRepository.session.collectAsStateWithLifecycle(initialValue = null)
    var splashDone by remember { mutableStateOf(false) }

    // Routing rule in one place, keyed on the signed-in *state* (not the
    // session object — token rotation emits a new value and must not
    // re-navigate). The current destination guards against duplicate hops.
    val signedIn = session != null
    LaunchedEffect(splashDone, signedIn) {
        if (!splashDone) return@LaunchedEffect
        val current = navController.currentDestination?.route
        if (signedIn && current != Routes.HOME) {
            navController.navigate(Routes.HOME) {
                popUpTo(0) { inclusive = true }
            }
        } else if (!signedIn && current != Routes.SIGN_IN) {
            navController.navigate(Routes.SIGN_IN) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = Routes.SPLASH) {
            composable(Routes.SPLASH) {
                SplashScreen(version = BuildConfig.VERSION_NAME)
                LaunchedEffect(Unit) {
                    delay(900)
                    splashDone = true
                }
            }
            composable(Routes.SIGN_IN) {
                SignInScreen(container = container, onSignedIn = { /* session flow reroutes */ })
            }
            composable(Routes.HOME) {
                HomeScreen(container = container)
            }
        }
    }
}
