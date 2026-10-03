package app.recly.windows.settings

import java.util.prefs.AbstractPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * docs/03 "Storage location": the local folder the user picked is a fact about this PC, kept where the
 * theme and the language are — and read back by the next launch. Over a node in memory: the real
 * store is the developer's own `java.util.prefs`, which a test has no business writing into.
 */
class PreferenceSettingsTest {

    @Test
    fun `the picked folder is kept for the next launch and can be taken back`() {
        val prefs = MemoryPreferences()
        val settings = PreferenceSettings(prefs)
        assertNull(settings.localFolder, "nothing is picked on a new install")

        settings.localFolder = "D:\\Recordings\\Recly"

        assertEquals("D:\\Recordings\\Recly", settings.localFolder)
        assertEquals("D:\\Recordings\\Recly", PreferenceSettings(prefs).localFolder, "a new launch reads it back")

        settings.localFolder = null

        assertNull(settings.localFolder)
        assertEquals(emptyList(), prefs.keys().toList(), "taking it back leaves nothing in the store")
    }

    /** The core reads a blank root as "none is picked" (`PathFolder`), and so does the store. */
    @Test
    fun `a blank path is no folder`() {
        val settings = PreferenceSettings(MemoryPreferences())

        settings.localFolder = "  "

        assertNull(settings.localFolder)
    }

    /** A root node with no children and no backing store: the values, and nothing else. */
    private class MemoryPreferences : AbstractPreferences(null, "") {
        private val values = mutableMapOf<String, String>()

        override fun putSpi(key: String, value: String) {
            values[key] = value
        }

        override fun getSpi(key: String): String? = values[key]

        override fun removeSpi(key: String) {
            values.remove(key)
        }

        override fun removeNodeSpi() = Unit

        override fun keysSpi(): Array<String> = values.keys.toTypedArray()

        override fun childrenNamesSpi(): Array<String> = emptyArray()

        override fun childSpi(name: String): AbstractPreferences = throw UnsupportedOperationException(name)

        override fun syncSpi() = Unit

        override fun flushSpi() = Unit
    }
}
