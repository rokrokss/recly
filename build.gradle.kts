import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

/**
 * The Play App Signing *upload* key (docs/development.md "Release signing"). The phone and the
 * watch must carry the same signature — Play pairs them only then (docs/11) — so it is read once
 * here and both app modules turn it into their release `signingConfig`. Never committed:
 * `local.properties` first, then the environment. Absent, release bundles are left unsigned rather
 * than falling back to the debug key.
 */
extra["uploadKey"] = run {
    val local = Properties().apply {
        file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun get(key: String, env: String): String? =
        local.getProperty("upload.$key") ?: System.getenv("REC_UPLOAD_$env")
    val storeFile = get("storeFile", "STORE_FILE") ?: return@run null
    Properties().apply {
        setProperty("storeFile", storeFile)
        setProperty("storePassword", get("storePassword", "STORE_PASSWORD") ?: error("upload.storePassword is missing"))
        setProperty("keyAlias", get("keyAlias", "KEY_ALIAS") ?: "upload")
        setProperty("keyPassword", get("keyPassword", "KEY_PASSWORD") ?: getProperty("storePassword"))
    }
}
