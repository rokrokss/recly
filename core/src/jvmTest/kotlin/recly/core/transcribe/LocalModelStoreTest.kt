package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult
import recly.core.platform.Transport

class LocalModelStoreTest {
    private val fs = FakeFileSystem()
    private val dir = "/models/current".toPath()
    private val big = ByteArray(9 * 1024 * 1024) { (it % 251).toByte() }
    private val small = "tokens".encodeToByteArray()
    private val served = mapOf("https://example.test/big" to big, "https://example.test/small" to small,
        "https://example.test/tokenizer/vocab" to small)
    private val requests = mutableListOf<String>()

    /** Serves byte ranges the way the model host does, and records each Range asked for. */
    private val transport = object : Transport {
        override suspend fun execute(plan: HttpPlan): HttpResult {
            val bytes = served.getValue(plan.url)
            val range = plan.headers.getValue("Range")
            requests += "${plan.url.substringAfterLast('/')} $range"
            val (from, to) = range.removePrefix("bytes=").split('-').map { it.toInt() }
            return HttpResult(206, emptyMap(), bytes.copyOfRange(from, minOf(to + 1, bytes.size)))
        }
    }

    private fun file(name: String, bytes: ByteArray, sha256: String = bytes.toByteString().sha256().hex()) =
        ModelFile(name, "https://example.test/$name", bytes.size.toLong(), sha256)

    private fun store(vararg files: ModelFile) = LocalModelStore(transport, fs, dir, files.toList(), Dispatchers.Unconfined)

    @Test
    fun `installs every file in ranged chunks`() = runBlocking {
        val store = store(file("big", big), file("small", small), file("tokenizer/vocab", small))
        assertFalse(store.installed())
        store.install()
        assertTrue(store.installed())
        assertTrue(fs.read(dir / "big") { readByteArray() }.contentEquals(big))
        assertTrue(fs.read(dir / "tokenizer" / "vocab") { readByteArray() }.contentEquals(small))
        assertEquals(listOf("big bytes=0-8388607", "big bytes=8388608-9437183", "small bytes=0-5", "vocab bytes=0-5"), requests)
        assertFalse(fs.exists(dir / "big.part"))
    }

    @Test
    fun `an interrupted download resumes from what is on disk`() = runBlocking {
        fs.createDirectories(dir)
        fs.write(dir / "big.part") { write(big, 0, 8 * 1024 * 1024) }
        store(file("big", big)).install()
        assertEquals(listOf("big bytes=8388608-9437183"), requests)
        assertTrue(fs.read(dir / "big") { readByteArray() }.contentEquals(big))
    }

    @Test
    fun `a file whose hash does not match is discarded and never installed`() = runBlocking {
        val store = store(file("small", small, sha256 = "0".repeat(64)))
        assertFailsWith<IllegalStateException> { store.install() }
        assertFalse(store.installed())
        assertFalse(fs.exists(dir / "small"))
        assertFalse(fs.exists(dir / "small.part"))
    }

    @Test
    fun `an installed file is not downloaded again`() = runBlocking {
        store(file("small", small)).install()
        requests.clear()
        store(file("small", small)).install()
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `a whole install removes the models an older build left, and a failed one keeps them`() = runBlocking {
        val old = "/models/older".toPath()
        fs.createDirectories(old)
        fs.write(old / "decoder.onnx") { writeUtf8("old") }
        assertFailsWith<IllegalStateException> { store(file("small", small, sha256 = "0".repeat(64))).install() }
        assertTrue(fs.exists(old / "decoder.onnx"))
        store(file("small", small)).install()
        assertFalse(fs.exists(old))
        assertTrue(fs.exists(dir / "small"))
    }

    @Test
    fun `the model takes an English language name, detects on its own, and has no Ukrainian`() {
        assertEquals("Korean", Qwen3Asr.hint("ko"))
        assertEquals("Chinese", Qwen3Asr.hint("zh-tw"))
        assertEquals("", Qwen3Asr.hint("auto"))
        assertEquals(null, Qwen3Asr.hint("uk"))
        assertFalse(recly.core.model.Language.UK in Qwen3Asr.languages)
        assertEquals(TranscriptionLanguages.explicit.size - 1, Qwen3Asr.languages.size)
    }
}
