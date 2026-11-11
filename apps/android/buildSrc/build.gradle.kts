plugins { java }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    implementation(gradleApi())
    testImplementation(libs.junit)
}
tasks.test { useJUnit() }
