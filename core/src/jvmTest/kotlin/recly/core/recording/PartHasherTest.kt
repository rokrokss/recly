package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class PartHasherTest {
    private val fs = FakeFileSystem()
    private val path = "/data/part.m4a".toPath()

    @Test
    fun hashesWithTheStandardTestVectors() = runBlocking {
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PartHasher.sha256(fs, path),
        )
        assertEquals("900150983cd24fb0d6963f7d28e17f72", PartHasher.md5(fs, path))
        fs.checkNoOpenFiles()
    }

    @Test
    fun hashesAFileLargerThanOneBuffer() = runBlocking {
        fs.createDirectories(path.parent!!)
        val content = "x".repeat(200_000)
        fs.write(path) { writeUtf8(content) }
        assertEquals(64, PartHasher.sha256(fs, path).length)
        assertEquals(32, PartHasher.md5(fs, path).length)
        fs.checkNoOpenFiles()
    }
}
