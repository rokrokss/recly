pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx publishes its Android and JVM builds only here (docs/05 "고정 처리 설정 도입"); nothing else
        // is looked up on JitPack.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.k2-fsa.sherpa-onnx") }
        }
    }
}

rootProject.name = "recly"

include(":core")
include(":android:recording")
include(":android:datalayer")
include(":android:app")
include(":android:wear")
include(":windows:app")
