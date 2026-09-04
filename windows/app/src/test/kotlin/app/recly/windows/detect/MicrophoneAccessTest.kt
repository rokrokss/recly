package app.recly.windows.detect

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/14 "권한" · deliverable 1: letting a desktop app have the microphone. The registry read
 * itself is JNA on a machine this lane cannot run on (M6-L3 "환경 제약"); what a test can hold still
 * is what the two values mean, and that is where a wrong answer costs the user an hour of silence.
 *
 * The fixtures are the strings Windows actually writes under
 * `…\CapabilityAccessManager\ConsentStore\microphone`.
 */
class MicrophoneAccessTest {

    @Test
    fun `the app's own switch decides, whatever the machine-wide one says`() {
        assertEquals(MicAccess.DENIED, MicrophoneAccess.decode(nonPackaged = "Deny", global = "Allow"))
        assertEquals(MicAccess.ALLOWED, MicrophoneAccess.decode(nonPackaged = "Allow", global = "Deny"))
    }

    /** A machine where nobody has ever opened the desktop-app switch falls back to the global one. */
    @Test
    fun `an unwritten desktop switch falls back to the machine-wide value`() {
        assertEquals(MicAccess.DENIED, MicrophoneAccess.decode(nonPackaged = null, global = "Deny"))
        assertEquals(MicAccess.ALLOWED, MicrophoneAccess.decode(nonPackaged = null, global = "Allow"))
    }

    /**
     * Nothing written anywhere is Windows' own default, which is "allow" — and [MicAccess.UNKNOWN]
     * rather than [MicAccess.ALLOWED] because the app has not been told, and it says nothing about
     * what it has not been told.
     */
    @Test
    fun `nothing written is unknown, and so is anything unexpected`() {
        assertEquals(MicAccess.UNKNOWN, MicrophoneAccess.decode(null, null))
        assertEquals(MicAccess.UNKNOWN, MicrophoneAccess.decode("Prompt", null))
        assertEquals(MicAccess.UNKNOWN, MicrophoneAccess.decode("", ""))
    }

    /** The registry is not case-consistent, and neither is anything that has ever written to it. */
    @Test
    fun `the value is read case-insensitively`() {
        assertEquals(MicAccess.DENIED, MicrophoneAccess.decode(" deny ", null))
        assertEquals(MicAccess.ALLOWED, MicrophoneAccess.decode("ALLOW", null))
    }

    /** The keys the guidance names, so the two never drift apart. */
    @Test
    fun `the desktop-app switch lives under the microphone consent store`() {
        assertEquals("${MicrophoneAccess.KEY}\\NonPackaged", MicrophoneAccess.NON_PACKAGED_KEY)
        assertEquals(true, MicrophoneAccess.KEY.endsWith("ConsentStore\\microphone"))
    }
}
