package com.anve.app.ui.onboarding

import com.anve.app.data.onboarding.OnboardingStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingRouterTest {

    @Test fun `unsigned session always routes to sign in`() {
        assertEquals(
            OnboardingGateDecision.SIGN_IN,
            resolveOnboardingGate(
                signedIn = false,
                userId = null,
                storedStatus = OnboardingStatus.NONE,
                hasCircle = null,
                circleCheckFailed = false,
                reopenRequested = false,
            ),
        )
    }

    @Test fun `completed or skipped users go directly to home unless reopened`() {
        listOf(OnboardingStatus.COMPLETED, OnboardingStatus.SKIPPED).forEach { status ->
            assertEquals(
                OnboardingGateDecision.HOME,
                resolveOnboardingGate(
                    signedIn = true,
                    userId = "u1",
                    storedStatus = status,
                    hasCircle = null,
                    circleCheckFailed = false,
                    reopenRequested = false,
                ),
            )
            assertEquals(
                OnboardingGateDecision.ONBOARDING,
                resolveOnboardingGate(
                    signedIn = true,
                    userId = "u1",
                    storedStatus = status,
                    hasCircle = true,
                    circleCheckFailed = false,
                    reopenRequested = true,
                ),
            )
        }
    }

    @Test fun `fresh user waits while checking circle then routes by membership or offline fallback`() {
        assertEquals(
            OnboardingGateDecision.CHECKING,
            resolveOnboardingGate(
                signedIn = true,
                userId = "u1",
                storedStatus = OnboardingStatus.NONE,
                hasCircle = null,
                circleCheckFailed = false,
                reopenRequested = false,
            ),
        )
        assertEquals(
            OnboardingGateDecision.HOME,
            resolveOnboardingGate(
                signedIn = true,
                userId = "u1",
                storedStatus = OnboardingStatus.NONE,
                hasCircle = true,
                circleCheckFailed = false,
                reopenRequested = false,
            ),
        )
        assertEquals(
            OnboardingGateDecision.ONBOARDING,
            resolveOnboardingGate(
                signedIn = true,
                userId = "u1",
                storedStatus = OnboardingStatus.NONE,
                hasCircle = false,
                circleCheckFailed = false,
                reopenRequested = false,
            ),
        )
        assertEquals(
            OnboardingGateDecision.ONBOARDING,
            resolveOnboardingGate(
                signedIn = true,
                userId = "u1",
                storedStatus = OnboardingStatus.NONE,
                hasCircle = null,
                circleCheckFailed = true,
                reopenRequested = false,
            ),
        )
    }
}
