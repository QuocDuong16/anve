package com.anve.app.ui.splash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun `tagline is not blank`() {
        assertTrue(SplashCopy.tagline.isNotBlank())
    }
}
