package app.recly.android.work

import androidx.work.NetworkType
import androidx.work.WorkInfo
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.w3c.dom.Element
import recly.core.model.Language
import recly.core.transcribe.LocalEngineInfo
import recly.core.transcribe.LocalEngineStatus

/**
 * The one model download every surface reads: what its work's state means on screen, and the
 * percentage and bytes those surfaces print.
 */
class ModelDownloadTest {

    @Test
    fun `the work's state is the download's phase`() {
        assertEquals(ModelDownloadPhase.DOWNLOADING, phaseOf(WorkInfo.State.RUNNING, NetworkType.CONNECTED))
        assertEquals(ModelDownloadPhase.DOWNLOADING, phaseOf(WorkInfo.State.RUNNING, NetworkType.UNMETERED))
        // Queued on any network is about to start; queued for Wi-Fi is the "Download on Wi-Fi" wait.
        assertEquals(ModelDownloadPhase.DOWNLOADING, phaseOf(WorkInfo.State.ENQUEUED, NetworkType.CONNECTED))
        assertEquals(ModelDownloadPhase.WAITING_FOR_WIFI, phaseOf(WorkInfo.State.ENQUEUED, NetworkType.UNMETERED))
        assertEquals(ModelDownloadPhase.FAILED, phaseOf(WorkInfo.State.FAILED, NetworkType.CONNECTED))
        // Cancelled keeps the partial files, and the surfaces offer the resume.
        assertEquals(ModelDownloadPhase.IDLE, phaseOf(WorkInfo.State.CANCELLED, NetworkType.CONNECTED))
        assertEquals(ModelDownloadPhase.IDLE, phaseOf(WorkInfo.State.SUCCEEDED, NetworkType.CONNECTED))
        assertEquals(ModelDownloadPhase.IDLE, phaseOf(null, null))
    }

    @Test
    fun `only a running or waiting download holds off a second start`() {
        assertTrue(ModelDownloadState(ModelDownloadPhase.DOWNLOADING).active)
        assertTrue(ModelDownloadState(ModelDownloadPhase.WAITING_FOR_WIFI).active)
        assertFalse(ModelDownloadState(ModelDownloadPhase.IDLE).active)
        assertFalse(ModelDownloadState(ModelDownloadPhase.FAILED).active)
    }

    @Test
    fun `the percentage is whole and bounded`() {
        assertEquals(0, modelPercent(null))
        assertEquals(0, modelPercent(0.004))
        assertEquals(42, modelPercent(0.4299))
        assertEquals(99, modelPercent(0.999))
        assertEquals(100, modelPercent(1.0))
    }

    @Test
    fun `the bytes on disk need both the size and the share`() {
        val total = 987_659_201L
        assertEquals(493_829_600L, downloadedBytes(info(total, 0.5)))
        assertNull(downloadedBytes(info(total, null)), "nothing partial on disk")
        assertNull(downloadedBytes(info(null, 0.5)), "a size the engine does not know")
        assertNull(downloadedBytes(null))
    }

    /** One way to write a size on every shell: whole MB below 1,000 MB, GB with one decimal from there. */
    @Test
    fun `sizes are whole megabytes, then gigabytes with one decimal`() {
        assertEquals("988 MB", modelSize(987_659_201L, Locale.ROOT))
        assertEquals("412 MB", modelSize(412_000_000L, Locale.ROOT))
        assertEquals("0 MB", modelSize(0L, Locale.ROOT))
        assertEquals("999 MB", modelSize(999_400_000L, Locale.ROOT))
        assertEquals("1.0 GB", modelSize(999_600_000L, Locale.ROOT), "never \"1000 MB\"")
        assertEquals("1.2 GB", modelSize(1_200_000_000L, Locale.ROOT))
        assertEquals("1.2 GB", modelSize(1_200_000_000L, Locale.KOREAN))
    }

    /** `KO_EN` is `ko-en` on the wire, as the core writes a step's language. */
    @Test
    fun `a language is written the way the core writes it`() {
        assertEquals("ko", Language.KO.wireTag())
        assertEquals("ko-en", Language.KO_EN.wireTag())
        assertEquals("zh-cn", Language.ZH_CN.wireTag())
    }

    /** The progress line and the bytes line, as the resources format them in both languages. */
    @Test
    fun `the progress and bytes lines read as the dictionary says`() {
        assertEquals("Downloading model… 42%", format("values", "processing_download_progress", 42))
        assertEquals("모델 다운로드 중… 42%", format("values-ko", "processing_download_progress", 42))
        assertEquals("412 MB of 988 MB", format("values", "processing_download_bytes", "412 MB", "988 MB"))
        assertEquals("988 MB 중 412 MB", format("values-ko", "processing_download_bytes", "412 MB", "988 MB"))
    }

    /** The settings notice and the mobile-data question carry the real size, not "about 1 GB". */
    @Test
    fun `the notice and the mobile-data question say the size`() {
        assertEquals("To transcribe on this device, download this model once (988 MB).", format("values", "processing_model_download", "988 MB"))
        assertEquals("이 기기에서 전사하려면 이 모델(988 MB)을 한 번 다운로드해야 합니다.", format("values-ko", "processing_model_download", "988 MB"))
        assertEquals("The model is 988 MB.", format("values", "processing_cellular_body", "988 MB"))
        assertEquals("모델은 988 MB입니다.", format("values-ko", "processing_cellular_body", "988 MB"))
    }

    private fun info(total: Long?, progress: Double?) =
        LocalEngineInfo(LocalEngineStatus.MODEL_REQUIRED, "qwen", "1", modelBytes = total, progress = progress)

    /** What `getString(id, args)` makes of the resource: a Java format string once aapt has unescaped it. */
    private fun format(qualifier: String, key: String, vararg args: Any): String {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$qualifier/strings.xml"))
            .getElementsByTagName("string")
        val value = (0 until nodes.length).map { nodes.item(it) as Element }
            .single { it.getAttribute("name") == key }.textContent
        return value.replace("\\'", "'").format(*args)
    }
}
