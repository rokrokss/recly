package app.recly.windows.detect

import app.recly.windows.core.Host
import app.recly.windows.i18n.Str
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import recly.core.platform.Logger

/** What Windows says about desktop apps and the microphone. */
enum class MicAccess {
    ALLOWED,
    DENIED,

    /** Nothing has ever been written — Windows' own default, which is "allow". */
    UNKNOWN,
}

/**
 * docs/14 "권한": there is no microphone prompt on Windows. Settings → Privacy → Microphone →
 * "Let desktop apps access your microphone" is a switch the user may have turned off years ago, and
 * with it off the capture helper opens a stream that returns silence forever. The only honest thing
 * an app can do is read the switch and say so.
 */
interface MicrophoneAccess {
    fun state(): MicAccess

    companion object {
        /** docs/lanes/M6-L3 deliverable 1. */
        const val KEY: String =
            "Software\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\microphone"

        /** Packaged (Store) apps are governed by [KEY]; a jpackage MSI is a `NonPackaged` app. */
        const val NON_PACKAGED_KEY: String = "$KEY\\NonPackaged"

        const val VALUE: String = "Value"

        val GUIDANCE: Str = Str.MIC_GUIDANCE

        /**
         * [nonPackaged] is this app's own switch and wins outright; [global] is the machine-wide one
         * and is only consulted when the first has never been written. `Allow`/`Deny` are the only
         * two strings Windows writes, and anything else is something this app should not guess at.
         */
        fun decode(nonPackaged: String?, global: String?): MicAccess {
            classify(nonPackaged)?.let { return it }
            return classify(global) ?: MicAccess.UNKNOWN
        }

        private fun classify(value: String?): MicAccess? = when (value?.trim()?.lowercase()) {
            "allow" -> MicAccess.ALLOWED
            "deny" -> MicAccess.DENIED
            else -> null
        }

        fun create(logger: Logger): MicrophoneAccess =
            if (Host.isWindows) WindowsMicrophoneAccess(logger) else UnknownMicrophoneAccess
    }
}

/** **Development host only.** macOS asks for the microphone with a prompt and a TCC entry, not a key. */
object UnknownMicrophoneAccess : MicrophoneAccess {
    override fun state(): MicAccess = MicAccess.UNKNOWN
}

/**
 * **Not exercised on the development host** — [MicrophoneAccess.create] picks
 * [UnknownMicrophoneAccess] there, and the decoding is what [MicrophoneAccess.decode] holds still
 * for a test.
 */
class WindowsMicrophoneAccess(private val logger: Logger) : MicrophoneAccess {

    override fun state(): MicAccess = MicrophoneAccess.decode(
        read(MicrophoneAccess.NON_PACKAGED_KEY),
        read(MicrophoneAccess.KEY),
    )

    /** A key that is not there is not an error: it is a machine where nobody has touched the switch. */
    private fun read(key: String): String? = runCatching {
        if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, MicrophoneAccess.VALUE)) {
            null
        } else {
            Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, key, MicrophoneAccess.VALUE)
        }
    }.getOrElse {
        logger.log(Logger.Level.WARN, "detect.micAccess.failed", mapOf("key" to key), it)
        null
    }
}
