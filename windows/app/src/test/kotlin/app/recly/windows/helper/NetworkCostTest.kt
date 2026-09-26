package app.recly.windows.helper

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `--network-cost`: the helper's one word before a model download, and everything that is not a
 * clear answer counting as "unknown" — which downloads without asking.
 */
class NetworkCostTest {

    @Test
    fun `the three words the helper prints`() {
        assertEquals(NetworkCost.METERED, NetworkCost.parse("metered\n"))
        assertEquals(NetworkCost.UNMETERED, NetworkCost.parse("unmetered\r\n"))
        assertEquals(NetworkCost.UNKNOWN, NetworkCost.parse("unknown\n"))
    }

    @Test
    fun `anything else is unknown`() {
        assertEquals(NetworkCost.UNKNOWN, NetworkCost.parse(null))
        assertEquals(NetworkCost.UNKNOWN, NetworkCost.parse(""))
        // An older helper that does not know the flag says so on stderr, which the call reads too.
        assertEquals(NetworkCost.UNKNOWN, NetworkCost.parse("recly-capture-helper: unknown argument --network-cost\n"))
        assertEquals(NetworkCost.UNKNOWN, NetworkCost.parse("Metered"))
    }

    @Test
    fun `the first line that says anything is the answer`() {
        assertEquals(NetworkCost.METERED, NetworkCost.parse("\n  metered  \nunmetered\n"))
    }

    /** The fake helper prints nothing for the flag, and a helper that will not run answers nothing. */
    @Test
    fun `a helper with no answer is unknown`() {
        assertEquals(NetworkCost.UNKNOWN, CaptureHelper.networkCost(FakeHelperCommand.command()))
        assertEquals(NetworkCost.UNKNOWN, CaptureHelper.networkCost(listOf("/nonexistent/recly-capture-helper")))
    }
}
