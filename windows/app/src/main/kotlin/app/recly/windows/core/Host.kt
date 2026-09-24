package app.recly.windows.core

import java.util.Locale
import okio.Path
import okio.Path.Companion.toPath

/**
 * The things this shell has to ask the machine it is running on. Windows is the target
 * (docs/14); macOS is the development host the lane is built and run on, and everything that only
 * Windows can do is behind an interface with a stub for it — see [SecureStores],
 * `app.recly.windows.settings.LaunchAtLogin`, `app.recly.windows.helper.HelperClient`.
 */
object Host {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** What every `meta.json` this install writes says produced it, and the Credential Manager prefix. */
    const val APP_ID: String = "app.recly.windows"

    /**
     * `%LOCALAPPDATA%\Recly` on Windows (docs/14 deliverable 1) — local, not roaming: the database,
     * the audio and the device id are this machine's and must not be copied onto another by a
     * roaming profile. Anywhere else is the development host, where it is the same Application
     * Support directory the Mac app uses, under this app's own id so the two never share a database.
     */
    fun dataDir(): Path = if (isWindows) {
        val local = System.getenv("LOCALAPPDATA")
            ?: (System.getProperty("user.home") + "\\AppData\\Local")
        local.toPath() / "Recly"
    } else {
        System.getProperty("user.home").toPath() / "Library" / "Application Support" / APP_ID
    }

    /** Full system language tag, preserving region and script for locale resolution. */
    fun language(): String = Locale.getDefault().toLanguageTag()

    /** The machine name in `meta.json` (docs/03 `deviceName`). */
    fun deviceName(): String =
        System.getenv(if (isWindows) "COMPUTERNAME" else "HOSTNAME")
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
            ?: "Windows PC"
}
