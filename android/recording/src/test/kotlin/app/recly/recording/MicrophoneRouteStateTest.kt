package app.recly.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MicrophoneRouteStateTest {
    private val builtIn = MicrophoneRoute(1, MicrophoneRoute.Kind.BUILT_IN, "Phone")
    private val bluetooth = MicrophoneRoute(2, MicrophoneRoute.Kind.BLUETOOTH, "Headset")
    private val usb = MicrophoneRoute(3, MicrophoneRoute.Kind.WIRED, "USB microphone")
    private val requests = mutableListOf<Int?>()
    private val state = MicrophoneRouteState()

    private fun update(
        inputs: List<MicrophoneRoute>, actual: MicrophoneRoute?, at: Long = 0, silenced: Boolean = false,
    ) = state.update(inputs, actual, at, silenced) { requests += it; true }

    @Test fun `bluetooth is requested but not reported as actual before the OS routes it`() {
        val result = update(listOf(builtIn, bluetooth), builtIn)
        assertEquals(bluetooth.id, result.requested)
        assertEquals(builtIn, result.actual)
        update(listOf(builtIn, bluetooth), bluetooth, 1_000)
        assertEquals(listOf<Int?>(bluetooth.id), requests)
    }

    @Test fun `wired input wins and unplugging falls back through bluetooth to system default`() {
        update(listOf(builtIn, bluetooth, usb), bluetooth)
        update(listOf(builtIn, bluetooth), bluetooth, 1_000)
        update(listOf(builtIn), builtIn, 2_000)
        assertEquals(listOf<Int?>(usb.id, bluetooth.id, null), requests)
    }

    @Test fun `keep the actual input among equally preferred devices`() {
        val second = bluetooth.copy(id = 5)
        update(listOf(builtIn, bluetooth, second), second)
        assertEquals(listOf<Int?>(second.id), requests)
    }

    @Test fun `an accepted but ineffective preference expires and is not retried every poll`() {
        update(listOf(builtIn, bluetooth), builtIn)
        assertFalse(update(listOf(builtIn, bluetooth), builtIn, 4_999).rejected)
        assertTrue(update(listOf(builtIn, bluetooth), builtIn, 5_000).rejected)
        repeat(10) { update(listOf(builtIn, bluetooth), builtIn, 6_000L + it) }
        assertEquals(listOf<Int?>(bluetooth.id, null), requests)
    }

    @Test fun `a disconnected rejected device can be tried when it returns`() {
        update(listOf(builtIn, bluetooth), builtIn)
        update(listOf(builtIn, bluetooth), builtIn, 5_000)
        state.devicesRemoved(setOf(bluetooth.id))
        update(listOf(builtIn, bluetooth), builtIn, 6_000)
        assertEquals(listOf<Int?>(bluetooth.id, null, bluetooth.id), requests)
    }

    @Test fun `a refused preference returns to the OS route immediately`() {
        val result = state.update(listOf(builtIn, bluetooth), builtIn, 0, false) {
            requests += it
            it == null
        }
        assertTrue(result.rejected)
        assertNull(result.requested)
        assertEquals(listOf<Int?>(bluetooth.id, null), requests)
        update(listOf(builtIn, bluetooth), builtIn, 6_000)
        assertEquals(2, requests.size)
    }

    @Test fun `unknown OS input is usable but is never selected as an external microphone`() {
        val vendor = MicrophoneRoute(9, MicrophoneRoute.Kind.OTHER, "Vendor input")
        update(listOf(vendor), vendor)
        val result = update(listOf(vendor), vendor, 30_000)
        assertFalse(result.unavailable)
        assertEquals(vendor, result.actual)
        assertEquals(listOf<Int?>(null), requests)
    }

    @Test fun `missing route resets preference once and reports once until recovery`() {
        update(emptyList(), null)
        update(emptyList(), null, 5_000)
        assertTrue(update(emptyList(), null, 10_000).unavailable)
        assertFalse(update(emptyList(), null, 11_000).unavailable)
        assertEquals(listOf<Int?>(null, null), requests)
        update(listOf(builtIn), builtIn, 12_000)
        update(emptyList(), null, 13_000)
        assertTrue(update(emptyList(), null, 23_000).unavailable)
    }

    @Test fun `a higher priority app silencing the microphone is not a missing route failure`() {
        update(emptyList(), null, silenced = true)
        assertFalse(update(emptyList(), null, 60_000, silenced = true).unavailable)
        assertEquals(listOf<Int?>(null), requests)
        assertFalse(update(listOf(builtIn), builtIn, 61_000).unavailable)
    }
}
