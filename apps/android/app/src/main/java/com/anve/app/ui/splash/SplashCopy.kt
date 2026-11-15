package com.anve.app.ui.splash

/**
 * Brand copy for the AnVề splash screen.
 *
 * The wordmark is kept in Kotlin rather than in `strings.xml` because it is a
 * product name that must never be translated, and because keeping it here
 * makes the copy assertable from a plain JVM unit test.
 *
 * The tagline deliberately does NOT live here: it is `splash_tagline` in
 * `strings.xml` because `SignInScreen` renders the same line — shared copy
 * needs one source of truth, and resources are the one Kotlin and
 * instrumented tests can both reach.
 */
object SplashCopy {
    /** The product wordmark. Not translatable. */
    const val displayName = "AnVề"

    /**
     * Formats the build version for display under the wordmark.
     *
     * @param version the semantic version of the running build.
     * @return a human readable label such as `Phiên bản 0.1.0`.
     */
    fun versionLabel(version: String): String = "Phiên bản $version"
}
