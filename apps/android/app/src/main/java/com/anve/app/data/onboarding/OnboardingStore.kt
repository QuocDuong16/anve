package com.anve.app.data.onboarding

import android.content.Context

enum class OnboardingStatus {
    NONE,
    COMPLETED,
    SKIPPED,
}

/**
 * Persists per-account first-run onboarding progress so returning users go
 * straight to Home while a newly signed-in account sees the setup flow once.
 */
interface OnboardingStorage {
    fun status(ownerId: String): OnboardingStatus
    fun setStatus(ownerId: String, status: OnboardingStatus)
}

class SharedPrefsOnboardingStorage(context: Context) : OnboardingStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun status(ownerId: String): OnboardingStatus {
        val raw = prefs.getString(key(ownerId), null) ?: return OnboardingStatus.NONE
        return OnboardingStatus.entries.firstOrNull { it.name == raw } ?: OnboardingStatus.NONE
    }

    override fun setStatus(ownerId: String, status: OnboardingStatus) {
        prefs.edit().putString(key(ownerId), status.name).apply()
    }

    private fun key(ownerId: String) = "onboarding_status_$ownerId"

    private companion object {
        const val PREFS_NAME = "anve_onboarding"
    }
}
