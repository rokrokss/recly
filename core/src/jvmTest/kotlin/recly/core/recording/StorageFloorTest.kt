package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/03 "Free storage on the device": the floors every shell holds a start and a running recording to. */
class StorageFloorTest {

    @Test
    fun `a start needs 200 MB`() {
        assertTrue(StorageFloor.tooLow(freeBytes = 199_999_999, recording = false))
        assertTrue(StorageFloor.tooLow(freeBytes = 0, recording = false))
        assertFalse(StorageFloor.tooLow(freeBytes = 200_000_000, recording = false))
        assertFalse(StorageFloor.tooLow(freeBytes = 64_000_000_000, recording = false))
    }

    @Test
    fun `a running recording goes on down to 50 MB`() {
        assertFalse(StorageFloor.tooLow(freeBytes = 150_000_000, recording = true), "too little to start, enough to go on")
        assertFalse(StorageFloor.tooLow(freeBytes = 50_000_000, recording = true))
        assertTrue(StorageFloor.tooLow(freeBytes = 49_999_999, recording = true))
        assertTrue(StorageFloor.tooLow(freeBytes = 0, recording = true), "a write that failed on a full disk")
    }

    /** As an unreadable battery: a figure the platform could not give refuses nothing and stops nothing. */
    @Test
    fun `a figure the platform could not read is never too low`() {
        assertFalse(StorageFloor.tooLow(freeBytes = -1, recording = false))
        assertFalse(StorageFloor.tooLow(freeBytes = -1, recording = true))
    }

    /** About 14 hours of 32 kbps mono (docs/03 "Audio settings": 14.4 MB an hour) is the start's floor. */
    @Test
    fun `the start floor is hours of audio, so the margin is the system's`() {
        val bytesPerHour = 32_000L / 8 * 3_600
        assertEquals(13, StorageFloor.START_BYTES / bytesPerHour)
    }
}
