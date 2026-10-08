package app.recly.windows.ui

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import recly.core.processing.ProcessingStorage

/**
 * The UX decisions of 2026-10-08, item 10: the Drive folder template is two chips, `Monthly folders` and
 * `One folder`, with today's folder under them — and the stored value stays the template.
 */
class StorageFolderTest {

    private val today = LocalDateTime.of(2026, 10, 8, 9, 30)

    @Test
    fun `the preview is where today's recording goes`() {
        assertEquals("recly/memo/2026-10", folderPreview(MONTHLY_FOLDER, today))
        assertEquals("recly/memo", folderPreview(SINGLE_FOLDER, today))
        // A custom template reads as Monthly and previews as itself, its date filled in.
        assertEquals("work/2026/08", folderPreview("work/{{yyyy}}/{{dd}}", today))
        // One that names something only a recording has is shown as it stands.
        assertEquals("recly/{{title}}", folderPreview("recly/{{title}}", today))
    }

    /** Monthly is the processing settings' own default, so a fresh install shows it chosen. */
    @Test
    fun `monthly is the default`() {
        assertEquals(MONTHLY_FOLDER, ProcessingStorage().folder)
    }
}
