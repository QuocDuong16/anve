// Root build file. Plugins are declared here with `apply false` so that the
// versions come from gradle/libs.versions.toml and each module can opt in.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
