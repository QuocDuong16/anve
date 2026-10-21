package com.anve.app.ui.splash

/**
 * Brand copy for the AnVề splash screen.
 *
 * The wordmark is kept in Kotlin rather than in `strings.xml` because it is a
 * product name that must never be translated, and because keeping it here
 * makes the copy assertable from a plain JVM unit test.
 */
object SplashCopy {
    /** The product wordmark. Not translatable. */
    const val displayName = "AnVề"

    /** Short Vietnamese tagline shown under the wordmark. */
    const val tagline = "Đường về nhà, gần hơn mỗi ngày"

    /**
     * Formats the build version for display under the wordmark.
     *
     * @param version the semantic version of the running build.
     * @return a human readable label such as `Phiên bản 0.1.0`.
     */
    fun versionLabel(version: String): String = "Phiên bản $version"
}
