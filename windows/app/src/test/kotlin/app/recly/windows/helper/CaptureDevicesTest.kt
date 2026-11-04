package app.recly.windows.helper

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `--devices` (2026-11-04): the helper's names for the microphone and the output a start would capture, and
 * everything that is not one of its two lines naming nothing — the tray then says nothing about them.
 */
class CaptureDevicesTest {

    @Test
    fun `the two lines the helper prints`() {
        assertEquals(
            CaptureDevices("Headset Microphone (Jabra)", "Speakers (Realtek(R) Audio)"),
            CaptureDevices.parse("microphone=Headset Microphone (Jabra)\r\noutput=Speakers (Realtek(R) Audio)\r\n"),
        )
        // A name may hold an `=` of its own; only the first one is the separator.
        assertEquals(CaptureDevices("Mic = USB", null), CaptureDevices.parse("microphone=Mic = USB\n"))
    }

    @Test
    fun `anything else names nothing`() {
        assertEquals(CaptureDevices.NONE, CaptureDevices.parse(null))
        assertEquals(CaptureDevices.NONE, CaptureDevices.parse(""))
        // An older helper that does not know the flag says so on stderr, which the call reads too.
        assertEquals(CaptureDevices.NONE, CaptureDevices.parse("recly-capture-helper: unknown argument --devices\n"))
        assertEquals(CaptureDevices.NONE, CaptureDevices.parse("microphone=\noutput=  \n"))
    }

    /** Off Windows the helper has no endpoints to name, and a helper that will not run names nothing. */
    @Test
    fun `a helper with no answer names nothing`() {
        assertEquals(CaptureDevices.NONE, CaptureHelper.devices(FakeHelperCommand.command()))
        assertEquals(CaptureDevices.NONE, CaptureHelper.devices(listOf("/nonexistent/recly-capture-helper")))
    }
}
