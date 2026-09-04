plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "app.recly.datalayer"
    compileSdk = 36
    // AGP 8.13 would otherwise auto-download build-tools 35; the SDK here ships 36.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 34
    }
}

/**
 * The wire between the phone and the watch, and nothing else. Both apps depend on it, so nothing
 * that belongs to one of them may land here — no Play Services, no Compose, no `Context`. What is
 * here is a grammar and three payload shapes, which is exactly what two devices have to agree on.
 */
dependencies {
    // `api`: `TransferPath` speaks in `recly.core.model.Track` and the acks in `recly.core.model.Part`.
    api(project(":core"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
