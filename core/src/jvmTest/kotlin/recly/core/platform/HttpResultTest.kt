package recly.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpResultTest {
    private val result = HttpResult(
        status = 308,
        headers = mapOf("Range" to listOf("bytes=0-1310719"), "X-GUploader-UploadID" to listOf("abc", "def")),
        body = ByteArray(0),
    )

    @Test
    fun looksHeadersUpWithoutCaringAboutCase() {
        assertEquals("bytes=0-1310719", result.header("range"))
        assertEquals("bytes=0-1310719", result.header("RANGE"))
        assertEquals("abc", result.header("x-guploader-uploadid"))
        assertNull(result.header("location"))
    }
}
