package app.recly.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/03 "보관 · 삭제": what the delete dialog and the disconnect warning count before they ask.
 * ADR-017's seven-day window outlives the upload, so the count follows the core's answer rather
 * than the files that happen to still be on this phone.
 */
class RetentionTest {

    @Test
    fun `parts still on disk a week after their upload are not counted`() {
        assertEquals(0, Retention.onlyHere(uploaded = true, partsOnDisk = 3))
    }

    @Test
    fun `parts Drive has not got are what the dialog leads with`() {
        assertEquals(3, Retention.onlyHere(uploaded = false, partsOnDisk = 3))
    }

    @Test
    fun `a recording with no files left here loses nothing`() {
        assertEquals(0, Retention.onlyHere(uploaded = false, partsOnDisk = 0))
    }
}
