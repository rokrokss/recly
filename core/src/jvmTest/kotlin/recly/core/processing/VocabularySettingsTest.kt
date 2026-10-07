package recly.core.processing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import recly.core.job.EnqueueResult
import recly.core.model.Step
import recly.core.testing.CoreFixture

/** docs/05 "Fixed processing settings": the vocabulary list. */
class VocabularySettingsTest {
    private val f = CoreFixture()

    private suspend fun save(vocabulary: List<String>): ProcessingSaveResult {
        val current = assertIs<ProcessingSettingsState.Ready>(f.core.processingSettings.read()).document
        return f.core.processingSettings.save(
            current.settings.copy(transcription = current.settings.transcription.copy(vocabulary = vocabulary)),
            current.revision,
        )
    }

    @Test
    fun `fifty trimmed entries of forty characters are the most a list holds`() = runBlocking {
        f.core.initializeProcessing()

        assertIs<ProcessingSaveResult.Saved>(save((1..50).map { "w$it" }))
        assertIs<ProcessingSaveResult.Saved>(save(listOf("x".repeat(40), "민수")))
        for (bad in listOf(
            (1..51).map { "w$it" },
            listOf("x".repeat(41)),
            listOf(" Recly"),
            listOf(""),
            listOf("a\nb"),
            listOf("Recly", "recly"),
        )) {
            assertIs<ProcessingSaveResult.Invalid>(save(bad), bad.toString())
        }
    }

    @Test
    fun `a refusal names the entry, not its text`() {
        val errors = ProcessingSettingsParser.vocabularyErrors(listOf("ok", " secret-ish "))

        assertEquals(listOf("vocabulary entry 2 must be one trimmed line of 1..40 characters"), errors)
    }

    @Test
    fun `the form trims and drops blank lines, and keeps the list across a save`() = runBlocking {
        val current = f.core.initializeProcessing().document
        val draft = ProcessingDraft.from(current.settings).apply { vocabulary = listOf(" Recly ", "", "민수") }

        val saved = assertIs<ProcessingSaveResult.Saved>(f.core.processingSettings.save(draft.settings(), current.revision))

        assertEquals(listOf("Recly", "민수"), saved.document.settings.transcription.vocabulary)
        assertEquals(listOf("Recly", "민수"), ProcessingDraft.from(saved.document.settings).vocabulary)
    }

    @Test
    fun `a recording keeps the list it started with, and a re-run takes the current one`() = runBlocking {
        f.core.initializeProcessing()
        save(listOf("first"))
        f.record()
        val job = assertIs<EnqueueResult.Enqueued>(f.core.enqueue(CoreFixture.ID)).jobId
        save(listOf("second"))

        val frozen = f.core.jobs.list().single { it.id == job }.workflow!!.steps.filterIsInstance<Step.LocalTranscribe>().single()
        assertEquals(listOf("first"), frozen.vocabulary)

        f.drain()
        assertTrue(f.engine!!.requests.all { it.vocabulary == listOf("first") })
        val again = assertIs<recly.core.transcribe.RetranscribeResult.Started>(f.core.retranscribe(CoreFixture.ID))
        val rerun = f.core.jobs.list().single { it.id == again.jobId }.workflow!!.steps.filterIsInstance<Step.LocalTranscribe>().single()
        assertEquals(listOf("second"), rerun.vocabulary)
    }
}
