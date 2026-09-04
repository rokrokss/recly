package app.recly.windows.detect

import app.recly.windows.core.Host
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import recly.core.platform.Logger

/**
 * The half of docs/14 "감지" that is not the microphone: what is running, and what its windows are
 * called. Read on demand rather than watched — the browser half of the answer is a window *title*,
 * which no notification would report anyway, and [MeetingDetector] is already ticking.
 */
interface RunningApps {
    /** Executable names (`Zoom.exe`), not paths. */
    fun processes(): Set<String>

    /** Titles of the visible top-level windows. Only asked for when a browser is the candidate. */
    fun windowTitles(): List<String>

    companion object {
        /**
         * A comma-separated process list that stands in for the real one. The development host is
         * macOS and has no `ms-teams.exe` to find (M6-L3 "환경 제약"), so this is how the detection
         * path is put in front of a person here — `windows/app/README.md`.
         */
        const val OVERRIDE_ENV: String = "RECLY_DETECT_PROCESSES"

        fun create(logger: Logger, env: (String) -> String? = System::getenv): RunningApps {
            env(OVERRIDE_ENV)?.takeIf { it.isNotBlank() }?.let { list ->
                val names = list.split(",").map(String::trim).filter(String::isNotEmpty).toSet()
                logger.log(Logger.Level.WARN, "detect.processes.faked", mapOf("apps" to names.size))
                return FixedApps(names)
            }
            return if (Host.isWindows) WindowsApps(logger) else FixedApps(emptySet())
        }
    }
}

/** The override, and the development host's answer: nothing is running that this app knows about. */
class FixedApps(private val names: Set<String>) : RunningApps {
    override fun processes(): Set<String> = names

    override fun windowTitles(): List<String> = emptyList()
}

/**
 * **Not exercised on the development host** — [RunningApps.create] picks [FixedApps] there.
 *
 * Processes come from the JDK rather than JNA: `ProcessHandle` already answers this on every
 * platform, and one Windows-only dependency (the window titles) is enough for one file.
 */
class WindowsApps(private val logger: Logger) : RunningApps {

    override fun processes(): Set<String> = runCatching {
        ProcessHandle.allProcesses()
            .map { handle -> handle.info().command().orElse("") }
            .filter { it.isNotEmpty() }
            .map { it.substringAfterLast('\\').substringAfterLast('/') }
            .collect(java.util.stream.Collectors.toSet())
    }.getOrElse {
        logger.log(Logger.Level.WARN, "detect.processes.failed", error = it)
        emptySet()
    }

    /**
     * `EnumWindows`, not the Accessibility tree: a window title is all docs/14 asks for, and it is
     * the one thing about another app's window that Windows hands over without a permission.
     */
    override fun windowTitles(): List<String> = runCatching {
        val titles = mutableListOf<String>()
        val buffer = CharArray(TITLE_MAX)
        User32.INSTANCE.EnumWindows({ window: HWND, _ ->
            if (User32.INSTANCE.IsWindowVisible(window)) {
                val length = User32.INSTANCE.GetWindowText(window, buffer, buffer.size)
                if (length > 0) titles += String(buffer, 0, length)
            }
            true
        }, null)
        titles
    }.getOrElse {
        logger.log(Logger.Level.WARN, "detect.titles.failed", error = it)
        emptyList()
    }

    private companion object {
        /** Longer than any title a browser puts a meeting's name in. */
        const val TITLE_MAX = 512
    }
}
