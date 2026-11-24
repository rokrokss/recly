package recly.core.privacy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import recly.core.chatgpt.ChatGptHarness
import recly.core.processing.ExternalTranscription
import recly.core.processing.ProcessingTranscription
import recly.core.processing.TranscriptionMode
import recly.core.testing.CoreFixture

/** docs/15 "iPhone providers": what the iPhone's record node checks before a start. */
class RecordingTargetsTest {

    private val chatGpt = ChatGptHarness()

    private fun fixture(requireTransferConsent: Boolean = true) =
        CoreFixture(chatGpt = chatGpt.server.transport(chatGpt.fs), requireTransferConsent = requireTransferConsent)

    private suspend fun CoreFixture.transcribe(mode: TranscriptionMode) {
        val current = core.initializeProcessing().document
        val transcription = ProcessingTranscription(mode = mode, external = ExternalTranscription("assemblyai", "key"))
        core.processingSettings.save(current.settings.copy(transcription = transcription), current.revision)
    }

    private val assemblyAi = TransferTarget("transcribe", "assemblyai", recly.core.transcribe.SttProviders.defaultEndpoint("assemblyai")!!)

    @Test
    fun `the provider the settings name is missing until it is allowed`() = runBlocking {
        val f = fixture()
        f.transcribe(TranscriptionMode.EXTERNAL)
        assertEquals(listOf(assemblyAi), f.core.missingRecordingTargets())
        f.core.transferConsents.grant(listOf(assemblyAi))
        assertTrue(f.core.missingRecordingTargets().isEmpty())
    }

    @Test
    fun `transcription on the device or off sends nowhere`() = runBlocking {
        val f = fixture()
        f.transcribe(TranscriptionMode.LOCAL)
        assertTrue(f.core.missingRecordingTargets().isEmpty())
        f.transcribe(TranscriptionMode.OFF)
        assertTrue(f.core.missingRecordingTargets().isEmpty())
    }

    @Test
    fun `the ChatGPT plan is missing while organizing is on`() = runBlocking {
        val f = fixture()
        f.transcribe(TranscriptionMode.LOCAL)
        chatGpt.signIn(account = f.core.chatGpt)
        assertTrue(f.core.missingRecordingTargets().isEmpty(), "signed in but not agreed: nothing is organized")
        f.core.notes.consent()
        assertEquals(listOf(TransferTargets.chatGptSummary()), f.core.missingRecordingTargets())
        f.transcribe(TranscriptionMode.OFF)
        assertTrue(f.core.missingRecordingTargets().isEmpty(), "no transcript, nothing to organize")
        f.transcribe(TranscriptionMode.EXTERNAL)
        f.core.transferConsents.grant(listOf(TransferTargets.chatGptSummary()))
        assertEquals(listOf(assemblyAi), f.core.missingRecordingTargets())
    }

    @Test
    fun `nothing is missing where no permission is asked`() = runBlocking {
        val f = fixture(requireTransferConsent = false)
        f.transcribe(TranscriptionMode.EXTERNAL)
        assertTrue(f.core.missingRecordingTargets().isEmpty())
    }
}
