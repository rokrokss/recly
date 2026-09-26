package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.testing.inMemoryDatabase
import recly.core.testing.testDeps
import recly.core.testing.testMeta

class WaveformPeaksTest {
    private val fs = FakeFileSystem()
    private val repository = RecordingRepository(inMemoryDatabase(), testDeps(fileSystem = fs))
    private val meta = testMeta()
    private val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()

    @Test
    fun `peaks come back within sixteen bits and a foreign file reads as none`() {
        val peaks = listOf(0f, 0.001f, 0.5f, 1f, 2f)
        val read = WaveformPeaks.decode(WaveformPeaks.encode(peaks))!!
        assertEquals(peaks.size, read.size)
        peaks.zip(read).forEach { (written, back) -> assertEquals(written.coerceIn(0f, 1f), back, 1f / 65535) }
        assertNull(WaveformPeaks.decode("not a waveform".encodeToByteArray()))
        assertNull(WaveformPeaks.decode(WaveformPeaks.encode(peaks).copyOf(10)), "a short file is not half a waveform")
    }

    @Test
    fun `a saved waveform is read back, and goes with the recording`() = runBlocking {
        repository.create(meta, dir)
        assertNull(repository.waveform(meta.recordingId))
        repository.saveWaveform(meta.recordingId, listOf(0.25f, 0.75f))
        assertEquals(2, repository.waveform(meta.recordingId)!!.size)
        assertFalse(fs.exists(dir / "${WaveformPeaks.FILE}.tmp"))

        assertTrue(repository.delete(meta.recordingId, deleteDrive = false) is DeleteResult.Deleted)
        assertFalse(fs.exists(dir / WaveformPeaks.FILE))
        repository.saveWaveform(meta.recordingId, listOf(1f))
        assertFalse(fs.exists(dir), "a recording deleted meanwhile gets no file")
    }
}
