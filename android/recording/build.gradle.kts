plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "app.recly.recording"
    compileSdk = 36
    // AGP 8.13 would otherwise auto-download build-tools 35; the SDK here ships 36.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 34
    }
}

// The watch shares this module, so nothing Compose and nothing phone-only may land here.
dependencies {
    api(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    // `AndroidSecureStore`, which both shells build their `CoreDeps` with. As on the phone
    // (ADR-008): deprecated upstream, replaced in a later lane.
    implementation(libs.androidx.security.crypto)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // A real core on the JVM: the in-memory JDBC driver and a fake disk, so the recovery scan is
    // tested against the same `RecordingRepository` the device runs.
    testImplementation(libs.okio.fakefilesystem)
    testImplementation(libs.sqldelight.sqlite.driver)
}
