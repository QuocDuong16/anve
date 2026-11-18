package com.anve.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.anve.app.AppContainer
import com.anve.app.BuildConfig
import com.anve.app.R
import com.anve.app.data.onboarding.OnboardingStatus
import com.anve.app.data.sos.IncomingPush
import com.anve.app.ui.common.AnVeCheckingState
import com.anve.app.ui.home.HomeScreen
import com.anve.app.ui.onboarding.OnboardingGateDecision
import com.anve.app.ui.onboarding.OnboardingScreen
import com.anve.app.ui.onboarding.resolveOnboardingGate
import com.anve.app.ui.signin.SignInScreen
import com.anve.app.ui.splash.SplashScreen
import com.anve.app.ui.update.AppUpdateUiState
import com.anve.app.ui.update.ForceUpdateDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private object Routes {
    const val SPLASH = "splash"
    const val SIGN_IN = "signin"
    const val CHECKING = "checking"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
}

/**
 * Root composable: splash, then the stored session decides between sign-in,
 * first-run onboarding, and home. The splash stays a brief brand moment, not
 * a loading gate — the session read is fast and cached.
 */
@Composable
fun AnVeApp(
    container: AppContainer,
    incomingPush: IncomingPush? = null,
    onPushConsumed: () -> Unit = {},
    updateState: AppUpdateUiState = AppUpdateUiState(),
    onStartAppUpdate: () -> Unit = {},
    onOpenPlayStore: () -> Unit = {},
    onExitForDeferredUpdate: () -> Unit = {},
) {
    val navController = rememberNavController()
    val session by container.authRepository.session.collectAsStateWithLifecycle(initialValue = null)
    val invitation by container.invitationInbox.invitation.collectAsStateWithLifecycle()
    var splashDone by remember { mutableStateOf(false) }

    val userId = session?.userId
    val signedIn = session != null
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, userId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && userId != null) {
                container.pushRegistrationCoordinator.syncIfPermitted(userId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var onboardingStatus by remember(userId) {
        mutableStateOf(userId?.let { container.onboardingStore.status(it) } ?: OnboardingStatus.NONE)
    }
    var hasCircle by remember(userId) { mutableStateOf<Boolean?>(null) }
    var circleCheckFailed by remember(userId) { mutableStateOf(false) }
    var reopenOnboarding by rememberSaveable(userId) { mutableStateOf(false) }

    LaunchedEffect(session?.accessToken) {
        if (session != null && session?.userId == null) {
            // Pre-M3 sessions acquire their account ID through the existing guarded profile read.
            runCatching { container.authRepository.me() }
        }
    }

    LaunchedEffect(userId, onboardingStatus) {
        if (userId == null || onboardingStatus != OnboardingStatus.NONE) return@LaunchedEffect
        try {
            val mine = container.circleRepository.myCircle(userId)
            val alreadyInCircle = mine.circle != null
            if (alreadyInCircle) {
                container.onboardingStore.setStatus(userId, OnboardingStatus.COMPLETED)
                onboardingStatus = OnboardingStatus.COMPLETED
            }
            hasCircle = alreadyInCircle
            circleCheckFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            circleCheckFailed = true
        }
    }

    val gateDecision = resolveOnboardingGate(
        signedIn = signedIn,
        userId = userId,
        storedStatus = onboardingStatus,
        hasCircle = hasCircle,
        circleCheckFailed = circleCheckFailed,
        reopenRequested = reopenOnboarding,
    )

    // Routing rule in one place, keyed on the gate decision (not the session
    // object — token rotation emits a new value and must not re-navigate).
    LaunchedEffect(splashDone, gateDecision) {
        if (!splashDone) return@LaunchedEffect
        val targetRoute = when (gateDecision) {
            OnboardingGateDecision.SIGN_IN -> Routes.SIGN_IN
            OnboardingGateDecision.CHECKING -> Routes.CHECKING
            OnboardingGateDecision.ONBOARDING -> Routes.ONBOARDING
            OnboardingGateDecision.HOME -> Routes.HOME
        }
        val current = navController.currentDestination?.route
        if (current != targetRoute) {
            navController.navigate(targetRoute) {
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
                SignInScreen(container = container, onSignedIn = { /* session flow reroutes */ }, invitationPending = invitation != null)
            }
            composable(Routes.CHECKING) {
                AnVeCheckingState(stringResource(R.string.onboarding_checking_setup))
            }
            composable(Routes.ONBOARDING) {
                val activeOwner = userId ?: return@composable
                OnboardingScreen(
                    container = container,
                    owner = activeOwner,
                    startWithPendingInvite = invitation != null && !reopenOnboarding,
                    onFinished = {
                        reopenOnboarding = false
                        onboardingStatus = container.onboardingStore.status(activeOwner)
                    },
                    onExitRequested = {
                        if (reopenOnboarding) {
                            reopenOnboarding = false
                        } else {
                            container.onboardingStore.setStatus(activeOwner, OnboardingStatus.SKIPPED)
                            onboardingStatus = OnboardingStatus.SKIPPED
                        }
                    },
                )
            }
            composable(Routes.HOME) {
                HomeScreen(
                    container = container,
                    incomingPush = incomingPush,
                    onPushConsumed = onPushConsumed,
                    onOpenOnboarding = { reopenOnboarding = true },
                )
            }
        }
        if (updateState.modalVisible) {
            ForceUpdateDialog(
                state = updateState,
                onConfirmUpdate = onStartAppUpdate,
                onOpenPlayStore = onOpenPlayStore,
                onExitApp = onExitForDeferredUpdate,
            )
        }
    }
}
