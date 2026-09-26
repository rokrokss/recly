package app.recly.windows.transcribe

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path.Companion.toOkioPath

class NativesTest {
    private val fs = FileSystem.SYSTEM
    private val root = Files.createTempDirectory("natives").toOkioPath()
    private val bundled = mapOf(
        System.mapLibraryName("onnxruntime") to "runtime".encodeToByteArray(),
        System.mapLibraryName("sherpa-onnx-jni") to "jni".encodeToByteArray(),
    )
    private val natives = Natives(fs, root / "native" / "rev-2") { name -> bundled[name]?.inputStream() }

    @AfterTest
    fun cleanUp() = fs.deleteRecursively(root)

    @Test
    fun `the libraries are written next to each other once`() {
        natives.load()
        bundled.forEach { (name, bytes) -> assertContentEquals(bytes, fs.read(root / "native" / "rev-2" / name) { readByteArray() }) }
        assertEquals((root / "native" / "rev-2").toString(), System.getProperty("sherpa_onnx.native.path"))
    }

    @Test
    fun `a library changed on disk is written again before it is loaded`() {
        natives.load()
        val jni = root / "native" / "rev-2" / System.mapLibraryName("sherpa-onnx-jni")
        fs.write(jni) { writeUtf8("planted") }
        natives.load()
        assertContentEquals(bundled.getValue(System.mapLibraryName("sherpa-onnx-jni")), fs.read(jni) { readByteArray() })
    }

    @Test
    fun `what an older build extracted is removed`() {
        val old = root / "native" / "rev-1"
        fs.createDirectories(old)
        fs.write(old / System.mapLibraryName("onnxruntime")) { writeUtf8("old") }
        natives.load()
        assertFalse(fs.exists(old))
        assertTrue(fs.exists(root / "native" / "rev-2"))
    }
}
