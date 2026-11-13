import com.anve.buildlogic.ReleaseInputs
import com.anve.buildlogic.VerifyReleaseInputs
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

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

fun releaseSetting(name: String): String = providers.environmentVariable(name)
    .orElse(providers.gradleProperty(name)).getOrElse("")
val releaseSettings = ReleaseInputs.PUBLIC_NAMES.associateWith(::releaseSetting)
// Gradle can store a configuration cache entry even after configuration fails.
// Never query signing credentials at all when either cache is requested.
val releaseCachesDisabled = !gradle.startParameter.isConfigurationCacheRequested && !gradle.startParameter.isBuildCacheEnabled
val signingEnvironment = (ReleaseInputs.SIGNING_NAMES + "ANVE_KEYSTORE_TYPE").associateWith {
    if (releaseCachesDisabled) providers.environmentVariable(it).getOrElse("") else ""
}
val signingComplete = ReleaseInputs.SIGNING_NAMES.all { signingEnvironment.getValue(it).isNotBlank() }
val verifyRelease = tasks.register<VerifyReleaseInputs>("verifyReleaseInputs") {
    signingCachesDisabled.set(releaseCachesDisabled)
    settings.set(releaseSettings)
    distribution.set(true)
    legacyConfig.set(layout.projectDirectory.file("src/main/res/values/config_local.xml"))
    sourceRoot.set(rootProject.layout.projectDirectory.dir("../.."))
}
val verifyReleaseValidation = tasks.register<VerifyReleaseInputs>("verifyReleaseValidationInputs") {
    signingCachesDisabled.set(releaseCachesDisabled)
    settings.set(releaseSettings)
    distribution.set(false)
    legacyConfig.set(layout.projectDirectory.file("src/main/res/values/config_local.xml"))
    sourceRoot.set(rootProject.layout.projectDirectory.dir("../.."))
}

val mapsApiKey = providers.gradleProperty("ANVE_MAPS_API_KEY")
    .orElse(providers.environmentVariable("ANVE_MAPS_API_KEY"))
    .getOrElse("")
val escapedMapsApiKey = mapsApiKey.replace("\\", "\\\\").replace("\"", "\\\"")
fun firebaseSetting(name: String): String = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name)).getOrElse("")
    .replace("\\", "\\\\").replace("\"", "\\\"")

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
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.anve.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = releaseSettings.getValue("ANVE_VERSION_CODE").toIntOrNull()?.takeIf { it > 0 } ?: 1
        versionName = releaseSettings.getValue("ANVE_VERSION_NAME").ifBlank { "0.2.0" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only debug retains the local emulator fallback. Release build types
        // override it and cannot package before their explicit-input guard.
        buildConfigField("String", "API_BASE_URL", ReleaseInputs.javaString(
            providers.gradleProperty("ANVE_API_URL").orElse(providers.environmentVariable("ANVE_API_URL"))
                .getOrElse("http://10.0.2.2:8080"),
        ))
        buildConfigField("String", "MAPS_API_KEY", "\"$escapedMapsApiKey\"")
        listOf("ANVE_FIREBASE_APP_ID", "ANVE_FIREBASE_PROJECT_ID", "ANVE_FIREBASE_API_KEY", "ANVE_FIREBASE_SENDER_ID").forEach { name ->
            buildConfigField("String", name, "\"${firebaseSetting(name)}\"")
        }
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }

    if (signingComplete) {
        signingConfigs.create("upload") {
            storeFile = file(signingEnvironment.getValue("ANVE_KEYSTORE_FILE"))
            storePassword = signingEnvironment.getValue("ANVE_KEYSTORE_PASSWORD")
            keyAlias = signingEnvironment.getValue("ANVE_KEY_ALIAS")
            keyPassword = signingEnvironment.getValue("ANVE_KEY_PASSWORD")
            storeType = signingEnvironment.getValue("ANVE_KEYSTORE_TYPE").ifBlank { "PKCS12" }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            ndk {
                debugSymbolLevel = "FULL"
            }
            buildConfigField("String", "API_BASE_URL", ReleaseInputs.javaString(releaseSettings.getValue("ANVE_API_URL")))
            resValue("string", "anve_google_server_client_id", releaseSettings.getValue("ANVE_GOOGLE_WEB_CLIENT_ID"))
            buildConfigField("String", "MAPS_API_KEY", ReleaseInputs.javaString(releaseSettings.getValue("ANVE_MAPS_API_KEY")))
            listOf("ANVE_FIREBASE_APP_ID", "ANVE_FIREBASE_PROJECT_ID", "ANVE_FIREBASE_API_KEY", "ANVE_FIREBASE_SENDER_ID").forEach { name ->
                buildConfigField("String", name, ReleaseInputs.javaString(releaseSettings.getValue(name)))
            }
            manifestPlaceholders["MAPS_API_KEY"] = releaseSettings.getValue("ANVE_MAPS_API_KEY")
            if (signingComplete) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("releaseValidation") {
            initWith(getByName("release"))
            applicationIdSuffix = ".releasevalidation"
            versionNameSuffix = "-validation"
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        resValues = true
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
    implementation(libs.maps.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Network: Retrofit + Moshi reflection (ADR 0003 — no compiler plugins,
    // compatible with AGP 9's built-in Kotlin).
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.moshi.kotlin)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    debugImplementation(libs.okhttp.logging)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Attach guards to the real variant lifecycle, not only an optional wrapper.
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyRelease) }
tasks.matching { it.name == "preReleaseValidationBuild" }.configureEach { dependsOn(verifyReleaseValidation) }

// Resolve the production graph too: fixture-only dependencies must not hide a
// new release SDK from the privacy review. Reports contain coordinates only.
androidComponents.onVariants { variant ->
    if (variant.name !in listOf("release", "releaseValidation")) return@onVariants
    val runtimeConfiguration = variant.runtimeConfiguration
    val modules = providers.provider {
        check(runtimeConfiguration.allDependencies.none { it is FileCollectionDependency }) {
            "Review the newly introduced local-file SDK before exporting release modules"
        }
        val graph = runtimeConfiguration.incoming.resolutionResult
        check(graph.allDependencies.none { it is UnresolvedDependencyResult }) {
            "Cannot review an unresolved release dependency graph"
        }
        graph.allComponents.filter { it.id != graph.rootComponent.get().id }.map { component ->
            val id = component.id as? ModuleComponentIdentifier
                ?: error("Review the newly introduced project dependency before exporting release SDKs")
            "${id.group}:${id.module}:${id.version}"
        }.distinct().sorted()
    }
    val report = layout.buildDirectory.file("outputs/privacy-review/${variant.name}-modules.txt")
    tasks.register("write${variant.name.replaceFirstChar { it.uppercase() }}PrivacyDependencies") {
        inputs.property("modules", modules)
        outputs.file(report)
        doLast {
            report.get().asFile.apply {
                parentFile.mkdirs()
                writeText(modules.get().joinToString("\n", postfix = "\n"))
            }
        }
    }
}
