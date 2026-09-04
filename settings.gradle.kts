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
    }
}

rootProject.name = "recly"

include(":core")
include(":android:recording")
include(":android:datalayer")
include(":android:app")
include(":android:wear")
include(":windows:app")
