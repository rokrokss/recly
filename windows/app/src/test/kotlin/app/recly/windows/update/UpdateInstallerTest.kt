package app.recly.windows.update

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/14 "Updates": the script the hidden PowerShell runs once the app has quit. It cannot be run here — there is no
 * PowerShell or msiexec on the development host — so what is checked is what it says: every path a PowerShell
 * single-quoted literal however it is spelled, the MSI's arguments quoted for msiexec, `INSTALLDIR` without the
 * backslash that would escape its closing quote, and the encoding `-EncodedCommand` takes.
 */
class UpdateInstallerTest {

    private val exe = "C:\\Users\\Mary O'Neil\\AppData\\Local\\Recly\\Recly.exe"
    private val updates = "C:\\Users\\Mary O'Neil\\AppData\\Local\\Recly\\updates"

    @Test
    fun `the script waits for the app, upgrades it in its own folder, and starts it again`() {
        val script = UpdateInstaller.script(
            pid = 4242,
            msi = "$updates\\Recly-0.3.3.msi",
            exe = exe,
            log = "$updates\\msiexec.log",
        )

        assertEquals(
            listOf(
                "Wait-Process -Id 4242 -ErrorAction SilentlyContinue",
                "try { Start-Process -FilePath 'msiexec.exe' -ArgumentList @(" +
                    "'/i'," +
                    "'\"C:\\Users\\Mary O''Neil\\AppData\\Local\\Recly\\updates\\Recly-0.3.3.msi\"'," +
                    "'/passive','/norestart'," +
                    "'INSTALLDIR=\"C:\\Users\\Mary O''Neil\\AppData\\Local\\Recly\"'," +
                    "'/l*v'," +
                    "'\"C:\\Users\\Mary O''Neil\\AppData\\Local\\Recly\\updates\\msiexec.log\"'" +
                    ") -Wait } catch { }",
                "Start-Process -FilePath 'C:\\Users\\Mary O''Neil\\AppData\\Local\\Recly\\Recly.exe'",
            ),
            script.split("\r\n"),
        )
    }

    @Test
    fun `a literal doubles every quote PowerShell reads as one, and nothing else is special in it`() {
        assertEquals("'C:\\Program Files\\Recly'", UpdateInstaller.literal("C:\\Program Files\\Recly"))
        assertEquals("'O''Neil'", UpdateInstaller.literal("O'Neil"))
        assertEquals("'O\u2019\u2019Neil'", UpdateInstaller.literal("O\u2019Neil"))
        assertEquals("'''x'''", UpdateInstaller.literal("'x'"))
        assertEquals("'\$env:TEMP `n \"x\"'", UpdateInstaller.literal("\$env:TEMP `n \"x\""))
    }

    /** msiexec reads `\"` as an escaped quote, which a folder ending in a backslash would put before the closing one. */
    @Test
    fun `INSTALLDIR is the folder of Recly exe with no trailing backslash`() {
        assertEquals("C:\\Users\\me\\AppData\\Local\\Recly", UpdateInstaller.installDir("C:\\Users\\me\\AppData\\Local\\Recly\\Recly.exe"))
        assertEquals("D:\\Apps\\Recly", UpdateInstaller.installDir("D:\\Apps\\Recly\\\\Recly.exe"))
        val script = UpdateInstaller.script(1, "m.msi", "D:\\My Apps\\Recly\\Recly.exe", "l.log")
        assertTrue("'INSTALLDIR=\"D:\\My Apps\\Recly\"'" in script, script)
    }

    @Test
    fun `the command runs the script hidden, encoded as UTF-16LE base64`() {
        val script = UpdateInstaller.script(7, "$updates\\Recly-0.3.3.msi", exe, "$updates\\msiexec.log") + " — 한글"

        val command = UpdateInstaller.command(script, powershell = "powershell.exe")

        assertEquals(
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-EncodedCommand"),
            command.dropLast(1),
        )
        val decoded = Base64.getDecoder().decode(command.last())
        assertEquals(script, String(decoded, Charsets.UTF_16LE))
        // Little-endian and no byte-order mark: `W` of `Wait-Process` is 0x57 0x00.
        assertEquals(listOf<Byte>(0x57, 0x00), decoded.take(2))
    }
}
