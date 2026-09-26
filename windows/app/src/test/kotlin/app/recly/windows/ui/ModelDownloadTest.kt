package app.recly.windows.ui

import app.recly.windows.plain
import app.recly.windows.helper.NetworkCost
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.coreMessage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import recly.core.message.CoreMessage
import recly.core.model.Language
import recly.core.processing.TranscriptionMode
import recly.core.transcribe.LocalEngineInfo
import recly.core.transcribe.LocalEngineStatus

/**
 * The speech model's one download in this process, and what the surfaces that offer it say about it:
 * the first-run card's conditions, the percentage and the bytes, and the metered question.
 */
class ModelDownloadTest {

    @Test
    fun `the card is there only while the model is really missing`() {
        assertTrue(card())
        assertFalse(card(mode = TranscriptionMode.EXTERNAL), "not on-device")
        assertFalse(card(mode = TranscriptionMode.OFF), "not transcribing at all")
        assertFalse(card(mode = null), "settings not read yet")
        assertFalse(card(installed = false), "no engine on this PC")
        assertFalse(card(status = LocalEngineStatus.READY), "the model is here")
        assertFalse(card(status = LocalEngineStatus.UNSUPPORTED), "the language is not the model's")
        assertFalse(card(status = null), "the engine has not answered yet")
        assertFalse(card(dismissed = true), "Not now")
        assertFalse(card(capturing = true), "a capture is running")
        assertFalse(card(waiting = true), "a recording is waiting, and the banner is the prompt")
    }

    /** The banner carries a running download; a waiting row repeating it would be a third copy. */
    @Test
    fun `a waiting row offers the download only while none runs`() {
        assertTrue(rowOffersDownload(running = false))
        assertFalse(rowOffersDownload(running = true))
    }

    @Test
    fun `the percentage is rounded down, and 0 before anything is on disk`() {
        val en = StringTable.of(StringTable.BASE)
        val ko = StringTable.of(StringTable.KOREAN)

        assertEquals("Downloading model… 0%", downloadingText(en, null))
        assertEquals("Downloading model… 42%", downloadingText(en, 0.4299))
        assertEquals("Downloading model… 99%", downloadingText(en, 0.999))
        assertEquals("모델 다운로드 중… 42%", downloadingText(ko, 0.42).plain())
    }

    @Test
    fun `the bytes line says what is here of the whole, only while part of it is`() {
        val en = StringTable.of(StringTable.BASE)
        val ko = StringTable.of(StringTable.KOREAN)
        val partly = info(progress = 0.5)

        assertEquals("494 MB of 988 MB", downloadedBytesText(en, partly))
        assertEquals("988 MB 중 494 MB", downloadedBytesText(ko, partly)?.plain())
        assertNull(downloadedBytesText(en, info(progress = null)), "nothing or everything is here")
        assertNull(downloadedBytesText(en, info(progress = 0.5).copy(modelBytes = null)), "a size the engine does not know")
        assertNull(downloadedBytesText(en, null))
    }

    @Test
    fun `a partly downloaded model is resumed, not downloaded`() {
        assertEquals(Str.PROCESSING_MODEL_RESUME, downloadLabel(info(progress = 0.3)))
        assertEquals(Str.PROCESSING_PREPARE, downloadLabel(info(progress = null)))
        assertEquals(Str.PROCESSING_PREPARE, downloadLabel(null))
    }

    /** Every shell writes a model size one way: whole MB under 1,000 MB, then GB with one decimal. */
    @Test
    fun `sizes are whole megabytes, then gigabytes with one decimal`() {
        assertEquals("988 MB", ByteFormat.format(MODEL_BYTES, Locale.ENGLISH))
        assertEquals("412 MB", ByteFormat.format(412_300_000, Locale.ENGLISH))
        assertEquals("0 MB", ByteFormat.format(0, Locale.ENGLISH))
        assertEquals("4 MB", ByteFormat.format(4_200_000, Locale.ENGLISH))
        assertEquals("999 MB", ByteFormat.format(999_400_000, Locale.ENGLISH))
        assertEquals("1.0 GB", ByteFormat.format(999_600_000, Locale.ENGLISH), "never 1000 MB")
        assertEquals("1.2 GB", ByteFormat.format(1_200_000_000, Locale.ENGLISH))
        assertEquals("1,3 GB", ByteFormat.format(1_300_000_000, Locale.GERMAN), "the language's own decimal mark")
    }

    @Test
    fun `the engine takes a language by its wire tag`() {
        assertEquals("ko", engineLanguage(Language.KO))
        assertEquals("zh-cn", engineLanguage(Language.ZH_CN))
        assertEquals("ko-en", engineLanguage(Language.KO_EN))
    }

    @Test
    fun `one download at a time, and the waiting recordings are due once it is done`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeEngine(prepare = { gate.await() })
        val download = fake.download(this)
        download.track("ko")

        download.start("en")
        until { download.running }
        // A second surface pressing Download while it runs starts nothing.
        download.start("ko")
        gate.complete(Unit)
        until { !download.running }

        assertEquals(listOf("en"), fake.prepared, "the row's own language, once")
        assertEquals(1, fake.due)
        assertEquals(LocalEngineStatus.READY, download.info?.status)
        assertNull(download.failure)
    }

    @Test
    fun `the progress is read back while it runs`() = runBlocking {
        val fake = FakeEngine(prepare = { awaitCancellation() })
        val download = fake.download(this)
        download.track("ko")
        fake.progress = 0.25

        download.start()
        until { download.info?.progress == 0.25 }
        fake.progress = 0.5
        until { download.info?.progress == 0.5 }
        download.cancel()
        until { !download.running }
    }

    @Test
    fun `without a language of its own it downloads in the saved settings' one`() = runBlocking {
        val fake = FakeEngine()
        val download = fake.download(this)

        download.start()
        delay(50)
        assertEquals(emptyList(), fake.prepared, "nothing saved to download in")

        download.track("ja")
        download.start()
        until { fake.prepared.isNotEmpty() && !download.running }
        assertEquals(listOf("ja"), fake.prepared)
    }

    @Test
    fun `a metered connection asks first, and nothing downloads until the answer is yes`() = runBlocking {
        val fake = FakeEngine(cost = NetworkCost.METERED)
        val download = fake.download(this)
        download.track("ko")

        download.start()
        until { download.meteredPrompt != null }
        assertEquals("ko", download.meteredPrompt)
        assertFalse(download.running)
        download.start()
        download.dismissMetered()
        delay(50)
        assertNull(download.meteredPrompt)
        assertEquals(emptyList(), fake.prepared, "Cancel downloads nothing")

        download.start()
        until { download.meteredPrompt != null }
        download.confirmMetered()
        until { fake.prepared.isNotEmpty() && !download.running }
        assertEquals(listOf("ko"), fake.prepared)
        assertNull(download.meteredPrompt)
    }

    @Test
    fun `an unmetered or unknown connection is not asked about`() = runBlocking {
        for (cost in listOf(NetworkCost.UNMETERED, NetworkCost.UNKNOWN)) {
            val fake = FakeEngine(cost = cost)
            val download = fake.download(this)
            download.start("ko")
            until { fake.prepared.isNotEmpty() && !download.running }
            assertNull(download.meteredPrompt, "$cost asked")
        }
    }

    @Test
    fun `a cancel stops it without calling it a failure`() = runBlocking {
        val fake = FakeEngine(prepare = { awaitCancellation() })
        val download = fake.download(this)

        download.start("ko")
        until { download.running }
        download.cancel()
        until { !download.running }

        assertNull(download.failure)
        assertEquals(0, fake.due, "nothing was released")
    }

    @Test
    fun `a failed download says why, and the next start clears it`() = runBlocking {
        var fail = true
        val fake = FakeEngine(prepare = { if (fail) error("model download of decoder.int8.onnx returned HTTP 503") })
        val download = fake.download(this)

        download.start("ko")
        until { download.failure != null && !download.running }
        assertEquals(
            coreMessage(CoreMessage.STEP_FAILED, "model download of decoder.int8.onnx returned HTTP 503"),
            download.failure,
        )
        assertEquals(0, fake.due)

        fail = false
        download.start("ko")
        until { fake.due == 1 && !download.running }
        assertNull(download.failure)
    }

    private fun card(
        mode: TranscriptionMode? = TranscriptionMode.LOCAL,
        installed: Boolean = true,
        status: LocalEngineStatus? = LocalEngineStatus.MODEL_REQUIRED,
        dismissed: Boolean = false,
        capturing: Boolean = false,
        waiting: Boolean = false,
    ) = showsModelCard(mode, installed, status, dismissed, capturing, waiting)

    private fun info(progress: Double?, status: LocalEngineStatus = LocalEngineStatus.MODEL_REQUIRED) =
        LocalEngineInfo(status, "qwen3-asr", "test", modelBytes = MODEL_BYTES, progress = progress)

    /** `ReclyCore`'s two calls and the helper's one, with what they were asked. */
    private inner class FakeEngine(
        val cost: NetworkCost = NetworkCost.UNKNOWN,
        val prepare: suspend () -> Unit = {},
    ) {
        val prepared = mutableListOf<String>()
        var due = 0
        @Volatile var ready = false
        @Volatile var progress: Double? = null

        fun download(scope: CoroutineScope) = ModelDownload(
            scope = scope,
            read = { info(progress, if (ready) LocalEngineStatus.READY else LocalEngineStatus.MODEL_REQUIRED) },
            prepare = { language ->
                prepared += language
                prepare()
                ready = true
                info(null, LocalEngineStatus.READY)
            },
            networkCost = { cost },
            onPrepared = { due++ },
            pollMs = 5,
        )
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(5)
    }

    private companion object {
        /** Qwen3-ASR's files together (`Qwen3Asr.files`). */
        const val MODEL_BYTES = 987_659_201L
    }
}
