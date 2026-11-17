package com.anve.app.ui.onboarding

import com.anve.app.data.onboarding.OnboardingStatus

enum class OnboardingGateDecision {
    SIGN_IN,
    CHECKING,
    ONBOARDING,
    HOME,
}

/**
 * Pure routing gate between Sign-In, Onboarding, and Home.
 *
 * Returning accounts (already completed/skipped locally or already belonging
 * to a Circle) skip directly to Home without showing onboarding. When the
 * initial Circle probe fails (e.g. offline), a fresh user still enters
 * Onboarding where "Tự khám phá ứng dụng" and offline states remain usable.
 */
fun resolveOnboardingGate(
    signedIn: Boolean,
    userId: String?,
    storedStatus: OnboardingStatus,
    hasCircle: Boolean?,
    circleCheckFailed: Boolean,
    reopenRequested: Boolean,
): OnboardingGateDecision = when {
    !signedIn -> OnboardingGateDecision.SIGN_IN
    reopenRequested -> OnboardingGateDecision.ONBOARDING
    userId == null -> OnboardingGateDecision.CHECKING
    storedStatus != OnboardingStatus.NONE -> OnboardingGateDecision.HOME
    hasCircle == true -> OnboardingGateDecision.HOME
    hasCircle == false || circleCheckFailed -> OnboardingGateDecision.ONBOARDING
    else -> OnboardingGateDecision.CHECKING
}
