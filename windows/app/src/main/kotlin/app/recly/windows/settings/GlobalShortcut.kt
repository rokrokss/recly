package app.recly.windows.settings

import app.recly.windows.core.Host
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import recly.core.platform.Logger

/** docs/14 "App": Ctrl+Alt+R, from any app, starts or stops a recording. */
interface GlobalShortcut {
    /** Starts listening; false when Windows refused the combination because another app holds it. */
    fun register(onPress: () -> Unit): Boolean

    fun unregister()

    companion object {
        /** What the settings row shows as the combination: a key name, not a sentence. */
        const val LABEL = "Ctrl+Alt+R"

        fun create(logger: Logger): GlobalShortcut = if (Host.isWindows) WindowsHotKey(logger) else NoGlobalShortcut
    }
}

/**
 * **Development host only.** macOS has no `RegisterHotKey`, and this app is not a macOS app: the row and its
 * switch are there, and pressing the keys does nothing (docs/14 "Development host (macOS) stand-ins").
 */
object NoGlobalShortcut : GlobalShortcut {
    override fun register(onPress: () -> Unit): Boolean = true

    override fun unregister() = Unit
}

/**
 * `RegisterHotKey` on a thread of its own: Windows posts `WM_HOTKEY` to the queue of the thread that
 * registered it, so that thread is the one that waits in `GetMessage` — and the one `WM_QUIT` ends.
 *
 * **Not exercised on the development host** — [GlobalShortcut.create] picks [NoGlobalShortcut] there.
 */
class WindowsHotKey(private val logger: Logger) : GlobalShortcut {
    private var thread: Thread? = null

    @Volatile private var threadId = 0

    @Synchronized
    override fun register(onPress: () -> Unit): Boolean {
        unregister()
        val registered = CompletableFuture<Boolean>()
        val listening = Thread({
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            val ok = User32.INSTANCE.RegisterHotKey(null, ID, WinUser.MOD_CONTROL or WinUser.MOD_ALT or WinUser.MOD_NOREPEAT, VK_R)
            registered.complete(ok)
            if (!ok) return@Thread
            try {
                val message = WinUser.MSG()
                while (User32.INSTANCE.GetMessage(message, null, 0, 0) > 0) {
                    if (message.message == WinUser.WM_HOTKEY && message.wParam.toInt() == ID) onPress()
                }
            } finally {
                User32.INSTANCE.UnregisterHotKey(null, ID)
            }
        }, "recly-hotkey").apply { isDaemon = true }
        listening.start()
        val ok = runCatching { registered.get(WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (ok) thread = listening
        logger.log(if (ok) Logger.Level.INFO else Logger.Level.WARN, if (ok) "shell.shortcut.registered" else "shell.shortcut.refused")
        return ok
    }

    @Synchronized
    override fun unregister() {
        val listening = thread ?: return
        thread = null
        User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        listening.join(WAIT_MS)
    }

    private companion object {
        const val ID = 1
        const val VK_R = 0x52
        const val WAIT_MS = 2_000L
    }
}
