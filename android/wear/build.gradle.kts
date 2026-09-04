plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "app.recly.wear"
    compileSdk = 36
    // AGP 8.13 would otherwise auto-download build-tools 35; the SDK here ships 36.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // docs/11: Play only pairs a watch APK with its phone APK when the application ID and the
        // signing key match. Neither module declares a signingConfig, so both take the debug key.
        applicationId = "app.recly"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-dev"
    }

    buildFeatures {
        compose = true
        // CoreDeps.appVersion.
        buildConfig = true
    }

    // The ViewModel test builds the real ViewModel against fakes; nothing it touches is a platform
    // class, but `Log` sits under the no-op transfer queue.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core"))
    // The recorder itself is shared with the phone (docs/11 W1): same service, same segments.
    implementation(project(":android:recording"))
    // docs/11 W4: the same channel paths the phone parses and the same acks it writes (M3-L2).
    implementation(project(":android:datalayer"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Wear Compose, not the phone's Material 3: different components, different layout rules.
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    // docs/11 W3: the watch-face chip.
    implementation(libs.wear.ongoing)
    // docs/11 W5: the tile and the complication. Both render outside this app's process, so
    // neither may use Compose — ProtoLayout for the tile, `ComplicationData` for the other.
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material)
    implementation(libs.wear.complications.datasource)
    // `CallbackToFutureAdapter`: `TileService` answers in a `ListenableFuture` and the tile's
    // counts come off a suspending queue.
    implementation(libs.androidx.concurrent.futures)
    // docs/11 W2: the workflow summary arrives on `/rec/workflows`; W4 sends the parts back.
    implementation(libs.play.services.wearable)
    // docs/11 W4: the sender runs in WorkManager, not in the screen — a transfer outlives both.
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The transfer queue is a file and the sender deletes files; both are tested on a fake disk.
    testImplementation(libs.okio.fakefilesystem)
}
