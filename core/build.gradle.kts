import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.skie)
}

// ADR-022 holds the apps to no telemetry, and the build holds itself to the same: SKIE otherwise captures
// and uploads analytics on every link — hardware, Git statistics, a hashed project id
// (https://skie.touchlab.co/Analytics, checked 2026-10-15) — and spends half a minute doing it.
skie {
    analytics {
        enabled.set(false)
    }
}

kotlin {
    jvmToolchain(21)

    jvm()
    androidTarget {
        publishLibraryVariants("release")
    }

    val xcf = XCFramework("ReclyCore")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        macosArm64(),
        watchosArm64(),
        watchosDeviceArm64(),
        watchosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "ReclyCore"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api`: these appear in the public surface shells compile against
            // (CoreDeps, StepOutput, DriverFactory, observe(): Flow, *Runtime.httpClient()).
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            api(libs.ktor.client.core)
            api(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
            api(libs.okio)
            implementation(libs.multiplatform.settings)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.sqlite.driver)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.json.schema.validator)
            implementation(libs.okio.fakefilesystem)
            implementation(libs.ktor.client.mock)
        }
    }
}

android {
    namespace = "app.recly.core"
    compileSdk = 36
    // AGP 8.13 would otherwise auto-download build-tools 35; the SDK here ships 36.
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 34
    }
}

// :core ships no instrumented tests — device tests live in the M2 app modules.
androidComponents {
    beforeVariants { variant -> variant.androidTest.enable = false }
}

tasks.register("assembleXCFramework") {
    dependsOn("assembleReclyCoreReleaseXCFramework")
}

// scripts/notes-eval: renders the notes bench's requests with the app's own prompt code — a main() in jvmTest that
// jvmTest compiles and never runs. scripts/notes-eval/notes_eval.py render runs it.
tasks.register<JavaExec>("notesEvalRender") {
    description = "Renders the scripts/notes-eval requests (args: <cases> <out> [<answers>])."
    val jvmCompilations = kotlin.jvm().compilations
    val test = jvmCompilations.getByName("test")
    classpath = files(test.output.allOutputs, jvmCompilations.getByName("main").output.allOutputs, test.runtimeDependencyFiles ?: files())
    mainClass.set("recly.core.notes.NotesEvalKt")
}

sqldelight {
    databases {
        create("RecDatabase") {
            packageName.set("recly.core.db")
        }
    }
}
