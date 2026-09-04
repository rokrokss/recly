import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The Web OAuth client ID is not a secret but it is per-developer, so it is never committed
 * (README.md). Order: `local.properties`, then the environment, then a placeholder that keeps the
 * build green and only makes sign-in fail.
 */
val googleServerClientId: String = run {
    val local = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    local.getProperty("google.serverClientId")
        ?: System.getenv("REC_GOOGLE_SERVER_CLIENT_ID")
        ?: "REPLACE_ME.apps.googleusercontent.com"
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "app.recly.android"
    compileSdk = 36
    // AGP 8.13 would otherwise auto-download build-tools 35; the SDK here ships 36.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.recly"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-dev"
        resValue("string", "google_server_client_id", googleServerClientId)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The worker test builds a real `WorkflowWorker` against a stub `Context` it never calls into
    // (the core and the scheduler are injected). Without this the stub android.jar throws on
    // construction instead of returning a default.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":android:recording"))
    // docs/11 A8 · W4: the channel path grammar and the ack payloads, shared with the watch app.
    implementation(project(":android:datalayer"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // docs/11 A9: the home widget. Glance is Compose for RemoteViews — the widget is the only
    // surface in this app that renders outside the app's own process.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // docs/08 "결과 파일": the detail screen plays the recording's parts back to back, which is
    // what an ExoPlayer playlist is — `MediaPlayer` would need the gaps stitched by hand.
    implementation(libs.androidx.media3.exoplayer)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.play.services.auth)
    // docs/11 A8: the Data Layer — `WearableListenerService`, `ChannelClient`, `DataClient`.
    implementation(libs.play.services.wearable)
    implementation(libs.googleid)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // TestListenableWorkerBuilder (docs/lanes M2-L3 deliverable 6). Robolectric comes with it as a
    // transitive test dependency; the worker test needs a real Context to construct the worker.
    testImplementation(libs.androidx.work.testing)
    // The staged-part rename is the one piece of the Data Layer path that touches the disk.
    testImplementation(libs.okio.fakefilesystem)

    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
