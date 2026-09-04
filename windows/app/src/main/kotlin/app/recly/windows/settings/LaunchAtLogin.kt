package app.recly.windows.settings

import app.recly.windows.core.Host
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import recly.core.platform.Logger

/**
 * docs/14 "앱": launch at login. The Mac's equivalent is `SMAppService` (docs/12 "실행기"), and it
 * has the rule this follows — the checkbox is drawn from what the system says afterwards, never
 * from what was asked, so a registration that did not take does not leave a ticked box behind.
 */
interface LaunchAtLogin {
    /** False where there is nothing to register with — the development host, an unpackaged run. */
    val supported: Boolean

    fun isEnabled(): Boolean

    /** Applies the change and answers with what the system says now. */
    fun set(enabled: Boolean): Boolean
}

object LaunchAtLogins {
    fun create(logger: Logger): LaunchAtLogin =
        if (Host.isWindows) WindowsRunKey(logger) else NoLaunchAtLogin
}

/**
 * **Development host only.** macOS has its own login items and this app is not a macOS app; the
 * setting is shown disabled rather than hidden, so what is missing on this host is visible
 * (M6-L1 "환경 제약").
 */
object NoLaunchAtLogin : LaunchAtLogin {
    override val supported: Boolean = false

    override fun isEnabled(): Boolean = false

    override fun set(enabled: Boolean): Boolean = false
}

/**
 * `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` (docs/14). HKCU and not HKLM: this is one
 * user's choice on one machine and needs no elevation.
 *
 * **Not exercised on the development host** — [LaunchAtLogins] picks [NoLaunchAtLogin] there. The
 * runtime check is the user's Windows PC / M6-L3.
 */
class WindowsRunKey(private val logger: Logger) : LaunchAtLogin {

    /**
     * jpackage's launcher, which is what has to be registered — registering `java.exe` would start
     * a JVM with no application in it. Absent when the app is run from Gradle, and then there is
     * nothing to register.
     */
    private val launcher: String? = System.getProperty("jpackage.app-path")

    override val supported: Boolean get() = launcher != null

    override fun isEnabled(): Boolean = runCatching {
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, VALUE)
    }.getOrDefault(false)

    override fun set(enabled: Boolean): Boolean {
        val path = launcher ?: return false
        runCatching {
            if (enabled) {
                // Quoted: the install path has a space in it (`C:\Program Files\Recly\Recly.exe`).
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE, "\"$path\"")
            } else if (isEnabled()) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE)
            }
        }.onFailure {
            logger.log(Logger.Level.ERROR, "shell.launchAtLogin.failed", mapOf("enable" to enabled), it)
        }
        return isEnabled()
    }

    private companion object {
        const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val VALUE = "Recly"
    }
}
