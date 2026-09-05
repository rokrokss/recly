package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okio.Path.Companion.toPath
import recly.core.model.DriveLocation
import recly.core.model.RecordingStatus
import recly.core.testing.testMeta

/** docs/03 "다른 기기의 녹음": the Drive folder a ledger row can open, off what the row knows. */
class RecordingRecordTest {
    private fun record(
        status: RecordingStatus = RecordingStatus.FINALIZED,
        remote: Boolean = false,
        driveFolderId: String? = null,
        drive: DriveLocation? = null,
    ) = RecordingRecord(
        id = "rec-1",
        meta = testMeta(status = status).copy(drive = drive),
        dir = "/tmp/rec-1".toPath(),
        driveFolderId = driveFolderId,
        remote = remote,
    )

    @Test
    fun `an adopted row links to the folder it was read from`() {
        assertEquals(
            "https://drive.google.com/drive/folders/1FolderId",
            record(remote = true, driveFolderId = "1FolderId").driveFolderUrl,
        )
    }

    @Test
    fun `the link the upload wrote into the meta is preferred over one built from the id`() {
        val drive = DriveLocation("1FolderId", "https://drive.google.com/drive/folders/1FolderId?usp=drive_link")
        assertEquals(drive.folderUrl, record(driveFolderId = "1FolderId", drive = drive).driveFolderUrl)
    }

    @Test
    fun `a row with no folder yet has no link`() {
        assertNull(record().driveFolderUrl)
    }

    /** docs/09 화면 원칙 2: the placeholder for another device's upload offers no actions. */
    @Test
    fun `a folder another device is still uploading into is not offered`() {
        assertNull(record(status = RecordingStatus.RECORDING, remote = true, driveFolderId = "1FolderId").driveFolderUrl)
    }
}
