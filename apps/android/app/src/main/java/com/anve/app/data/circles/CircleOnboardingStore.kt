package com.anve.app.data.circles

import android.content.Context

/**
 * Persists per-account activation checklist progress (whether viewer grants
 * have been confirmed on the server or the user chose to dismiss the card)
 * so reopening the app never flashes or repeats finished onboarding.
 */
interface CircleOnboardingStorage {
    fun isAudienceConfigured(ownerId: String): Boolean
    fun setAudienceConfigured(ownerId: String, configured: Boolean)
    fun isChecklistDismissed(ownerId: String): Boolean
    fun setChecklistDismissed(ownerId: String, dismissed: Boolean)
}

class SharedPrefsCircleOnboardingStorage(context: Context) : CircleOnboardingStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isAudienceConfigured(ownerId: String): Boolean =
        prefs.getBoolean(audienceKey(ownerId), false)

    override fun setAudienceConfigured(ownerId: String, configured: Boolean) {
        prefs.edit().putBoolean(audienceKey(ownerId), configured).apply()
    }

    override fun isChecklistDismissed(ownerId: String): Boolean =
        prefs.getBoolean(dismissedKey(ownerId), false)

    override fun setChecklistDismissed(ownerId: String, dismissed: Boolean) {
        prefs.edit().putBoolean(dismissedKey(ownerId), dismissed).apply()
    }

    private fun audienceKey(ownerId: String) = "audience_configured_$ownerId"
    private fun dismissedKey(ownerId: String) = "checklist_dismissed_$ownerId"

    private companion object {
        const val PREFS_NAME = "anve_circle_onboarding"
    }
}
