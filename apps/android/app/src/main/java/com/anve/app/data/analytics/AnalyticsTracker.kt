package com.anve.app.data.analytics

import android.content.Context
import android.os.Bundle
import com.anve.app.data.sos.FirebaseSetup
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Privacy-bounded Google Analytics / Firebase Analytics wrapper.
 *
 * Only logs non-identifying UI navigation/feature counts once [FirebaseSetup]
 * confirms explicit Firebase build inputs are present. Callers must never pass
 * coordinates, custom place names, emails, display names, or account IDs.
 */
object AnalyticsTracker {

    fun initialize(context: Context) {
        FirebaseSetup.initialize(context)
    }

    fun logTabViewed(context: Context, tabName: String) {
        logEvent(context, "home_tab_viewed", mapOf("tab_name" to tabName))
    }

    fun logEvent(context: Context, name: String, params: Map<String, String> = emptyMap()) {
        if (!FirebaseSetup.initialize(context)) return
        runCatching {
            val bundle = Bundle().apply {
                params.forEach { (key, value) -> putString(key, value) }
            }
            FirebaseAnalytics.getInstance(context.applicationContext).logEvent(name, bundle)
        }
    }
}
