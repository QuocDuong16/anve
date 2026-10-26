plugins {
    alias(libs.plugins.android.application)
    // The Kotlin Android plugin is deliberately absent: AGP 9 compiles the
    // Kotlin sources through its built-in Kotlin support. The Compose
    // compiler plugin still has to be applied, because it is a separate
    // artifact from the Kotlin compiler itself.
    alias(libs.plugins.kotlin.compose)
    // Room generates its DAO/database implementations at build time. KSP is
    // the only supported annotation processor for Kotlin now that the Kotlin
    // Android plugin (and therefore kapt) is gone.
    alias(libs.plugins.ksp)
}

ksp {
    // Exported schemas make every Room version reviewable in the diff and
    // are the input for future migration tests. A destructive migration is
    // never enabled: the offline location queue must survive app updates.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.anve.app"
    compileSdk = libs.versions.compileSdk.get().toInt()
    // Pinned explicitly: AGP would otherwise pick its own default, which CI
    // would then have to guess when installing the Android SDK.
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "com.anve.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The API the app talks to. The debug default points at the host
        // machine from inside an emulator (10.0.2.2); override with
        // -PANVE_API_URL=https://... or gradle.properties for a real device
        // or a deployed backend. Release builds must be pointed at a real
        // deployment at build time — see docs/implementation/setup-android.md.
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"${project.findProperty("ANVE_API_URL") ?: "http://10.0.2.2:8080"}\"",
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // AGP 8 no longer generates BuildConfig by default, but the app
        // reports its version on the splash screen and reads API_BASE_URL.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material.icons.extended)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Sign-in: Credential Manager with the Google ID-token flow; the token
    // is verified server-side (ADR 0002).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    // Encrypted-at-rest session storage (ADR 0002).
    implementation(libs.androidx.datastore.preferences)

    // M3: durable offline location queue (Room) and scheduled background
    // work (WorkManager). The location engine itself uses the Play Services
    // FusedLocationProvider.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.play.services.location)

    // Network: Retrofit + Moshi reflection (ADR 0003 — no compiler plugins,
    // compatible with AGP 9's built-in Kotlin).
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.moshi.kotlin)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.okhttp.logging)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
