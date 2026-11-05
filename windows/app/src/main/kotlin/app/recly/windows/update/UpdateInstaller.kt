package app.recly.windows.update

import java.io.IOException
import java.nio.file.Path
import java.util.Base64

/**
 * docs/14 "Updates": what takes over once the app has quit — a hidden PowerShell that waits for this process to be
 * gone, runs the MSI's upgrade into the folder this copy is installed in, and starts Recly again whether or not the
 * upgrade went through, so the user is never left without the app.
 *
 * The folder is passed as `INSTALLDIR` — jpackage's WiX id for the app's folder — because its per-user MSI does not
 * remember a folder chosen at the first install, and an upgrade without it would go to `%LOCALAPPDATA%\Recly` and
 * leave the old copy where it was. A per-user install needs no elevation, so there is no UAC prompt.
 */
object UpdateInstaller {

    /**
     * Starts the installer, detached and hidden, and returns at once — the caller quits next. [exe] is
     * `jpackage.app-path`, the installed `Recly.exe`; [dir] is `<dataDir>\updates`, where the installer's log goes and
     * where PowerShell runs, so nothing holds the install folder open while the MSI replaces it.
     */
    fun launch(msi: Path, exe: String, dir: Path) {
        val script = script(
            pid = ProcessHandle.current().pid(),
            msi = msi.toString(),
            exe = exe,
            log = dir.resolve(LOG).toString(),
        )
        try {
            val process = ProcessBuilder(command(script))
                .directory(dir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            // A PowerShell whose standard input is a pipe can wait on it until it closes, so it is closed at once.
            process.outputStream.close()
        } catch (e: IOException) {
            throw UpdateException(UpdateFailure.INSTALL, "launch", e)
        }
    }

    /** The script, every path in it a PowerShell single-quoted literal ([literal]). */
    fun script(pid: Long, msi: String, exe: String, log: String): String {
        val arguments = listOf("/i", "\"$msi\"", "/passive", "/norestart", "INSTALLDIR=\"${installDir(exe)}\"", "/l*v", "\"$log\"")
        return listOf(
            "Wait-Process -Id $pid -ErrorAction SilentlyContinue",
            // In a try, so an installer that would not start still ends in the relaunch below.
            "try { Start-Process -FilePath ${literal("msiexec.exe")} -ArgumentList @(${arguments.joinToString(",", transform = ::literal)}) -Wait } catch { }",
            "Start-Process -FilePath ${literal(exe)}",
        ).joinToString("\r\n")
    }

    /** What `-EncodedCommand` takes: base64 of the script in UTF-16LE. */
    fun encode(script: String): String = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

    fun command(script: String, powershell: String = powershell()): List<String> = listOf(
        powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
        "-EncodedCommand", encode(script),
    )

    /**
     * The folder `Recly.exe` is in, with no trailing backslash: msiexec reads `\"` as an escaped quote, so
     * `INSTALLDIR="C:\…\Recly\"` would run on into the next argument.
     */
    internal fun installDir(exe: String): String = exe.substringBeforeLast('\\').trimEnd('\\')

    /**
     * [text] as a PowerShell single-quoted literal: nothing in it is expanded, and a quote is doubled — PowerShell
     * takes the typographic single quotes for quotes too, so they are doubled as well.
     */
    internal fun literal(text: String): String = "'" + text.replace(QUOTES) { it.value + it.value } + "'"

    private val QUOTES = Regex("['\u2018\u2019\u201A\u201B]")

    private const val LOG = "msiexec.log"

    /** Windows PowerShell by its own path rather than by a search that starts in the app's folder. */
    private fun powershell(): String =
        System.getenv("SystemRoot")?.let { "$it\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" } ?: "powershell.exe"
}
