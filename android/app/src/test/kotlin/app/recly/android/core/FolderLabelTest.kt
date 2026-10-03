package app.recly.android.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/03 "Storage location": what the settings row calls the picked tree, and how a core path is walked
 * through it — the two pieces of the Storage Access Framework folder that need no device.
 */
class FolderLabelTest {

    @Test
    fun `a folder on the device's storage goes by its path`() {
        assertEquals("Documents/Notes", folderLabel(EXTERNAL_STORAGE_AUTHORITY, "primary:Documents/Notes", "Notes"))
        assertEquals("Music/Recly", folderLabel(EXTERNAL_STORAGE_AUTHORITY, "1A2B-3C4D:Music/Recly", "Recly"))
    }

    @Test
    fun `a volume's root and another provider's tree go by the folder's name`() {
        assertEquals("Pixel 9", folderLabel(EXTERNAL_STORAGE_AUTHORITY, "primary:", "Pixel 9"))
        assertEquals("Notes", folderLabel("com.example.provider", "acc=1;doc=8f3a", "Notes"))
        // With no name to show, the id is still something to tell two folders apart by.
        assertEquals("acc=1;doc=8f3a", folderLabel("com.example.provider", "acc=1;doc=8f3a", null))
    }

    @Test
    fun `a core path is its folder names in order`() {
        assertEquals(emptyList(), folderSegments(""))
        assertEquals(listOf("recly", "memo", "2026-10-03_0900"), folderSegments("recly/memo/2026-10-03_0900"))
        assertEquals(listOf("a", "b.meta.json"), folderSegments("/a//b.meta.json"))
    }
}
