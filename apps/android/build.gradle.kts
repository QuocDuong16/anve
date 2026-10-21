// Root build file. Plugins are declared here with `apply false` so that the
// versions come from gradle/libs.versions.toml and each module can opt in.
//
// AGP 9 ships built-in Kotlin support, so the Kotlin Android plugin is no
// longer applied anywhere in this project.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
