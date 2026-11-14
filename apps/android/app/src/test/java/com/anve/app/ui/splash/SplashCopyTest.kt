package com.anve.app.ui.splash

import org.junit.Assert.assertEquals
import org.junit.Test

// Plain JVM tests cannot resolve Android string resources, so only the
// Kotlin-held brand copy is asserted here. Resource-backed copy such as the
// splash tagline (strings.xml: splash_tagline) is exercised by the
// instrumented branding tests instead.
class SplashCopyTest {

    @Test
    fun `display name is the product wordmark`() {
        assertEquals("AnVề", SplashCopy.displayName)
    }

    @Test
    fun `version label prefixes the version`() {
        assertEquals("Phiên bản 0.1.0", SplashCopy.versionLabel("0.1.0"))
    }

    @Test
    fun `version label keeps an empty version readable`() {
        assertEquals("Phiên bản ", SplashCopy.versionLabel(""))
    }
}
