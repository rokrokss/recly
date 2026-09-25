@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.processing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import recly.core.job.type
import recly.core.model.Language
import recly.core.model.Speakers
import recly.core.model.Step
import recly.core.testing.FakeClock
import recly.core.testing.inMemoryDatabase
import recly.core.testing.testDeps
import recly.core.workflow.WorkflowParser

class ProcessingSettingsTest {
    private class Harness {
        val db = inMemoryDatabase()
        val deps = testDeps(clock = FakeClock(), deviceId = "device-a")
    }

    @Test
    fun `global settings round trip and constrained providers reject unsupported new languages`() {
        for (language in recly.core.transcribe.TranscriptionLanguages.explicit) {
            val original = document().copy(settings = ProcessingSettings(transcription = ProcessingTranscription(
                mode = TranscriptionMode.EXTERNAL, language = language, external = ExternalTranscription("elevenlabs", "key"))))
            val parsed = assertIs<ProcessingParseResult.Valid>(ProcessingSettingsParser.parse(ProcessingSettingsParser.serialize(original)))
            assertEquals(language, parsed.document.settings.transcription.language)
        }
        val unsupported = document().copy(settings = ProcessingSettings(transcription = ProcessingTranscription(
            mode = TranscriptionMode.EXTERNAL, language = Language.FR, external = ExternalTranscription("clova", "key", "https://example.com/recognizer"))))
        assertIs<ProcessingParseResult.Invalid>(ProcessingSettingsParser.parse(ProcessingSettingsParser.serialize(unsupported)))
        assertEquals(listOf("elevenlabs", "clova"), WorkflowParser.STT_PROVIDERS.take(2))
        assertEquals("elevenlabs", ProcessingDraft.from(ProcessingSettings()).provider)
        assertEquals(Language.ZH_TW, recly.core.transcribe.TranscriptionLanguages.preferred("zh-Hant-HK"))
        assertEquals(Language.PT, recly.core.transcribe.TranscriptionLanguages.preferred("pt-BR"))
        assertEquals(Language.ZH_CN, recly.core.transcribe.TranscriptionLanguages.preferred("zh-Hans-HK"))
        assertFalse(Language.AUTO in recly.core.transcribe.TranscriptionLanguages.supported("rev"))
    }

    @Test
    fun `new plans derive speaker separation from the selected provider`() {
        val cases = mapOf(
            "assemblyai" to true, "azure" to true, "clova" to true, "daglo" to true,
            "deepgram" to true, "elevenlabs" to true, "gladia" to true, "rev" to true,
            "rtzr" to true, "speechmatics" to true, "openai" to true, "groq" to false,
            "together" to true, "mistral" to true,
        )
        for ((provider, expected) in cases) {
            val old = document().copy(settings = ProcessingSettings(
                transcription = ProcessingTranscription(mode = TranscriptionMode.EXTERNAL, diarize = false,
                    speakers = Speakers(4, 4), external = ExternalTranscription(provider, "key")),
            ))
            val plan = ProcessingPlan.compile(old)
            assertEquals(listOf("drive.upload", "transcribe", "transcript.publish"), plan.steps.map { it.type })
            val speech = assertIs<Step.Transcribe>(plan.steps[1])
            assertEquals(expected, speech.diarize, provider)
            assertEquals(Speakers(), speech.speakers, "infer speakers instead of imposing a hidden old count")
            assertFalse(old.settings.transcription.diarize, "the saved input snapshot is not mutated")
        }
        for ((model, expected) in mapOf("whisper-1" to false, "gpt-4o-transcribe" to false,
            "gpt-4o-transcribe-diarize" to true)) {
            val draft = ProcessingDraft.from(ProcessingSettings(transcription = ProcessingTranscription(
                mode = TranscriptionMode.EXTERNAL, external = ExternalTranscription("openai", "key", model = model))))
            assertEquals(expected, draft.settings().transcription.diarize, model)
            assertEquals(model, draft.settings().transcription.external!!.model)
        }
    }

    @Test
    fun `existing preferences and imported files cannot restore removed speaker controls`() = runBlocking {
        val h = Harness()
        val repository = ProcessingSettingsRepository(h.db, h.deps)
        val old = document().copy(settings = ProcessingSettings(
            transcription = ProcessingTranscription(mode = TranscriptionMode.EXTERNAL, diarize = false,
                external = ExternalTranscription("assemblyai", "key")),
        ))
        val raw = ProcessingSettingsParser.serialize(old)
        h.db.recQueries.syncSet(ProcessingSettingsRepository.KEY, raw)
        val current = assertIs<ProcessingSettingsState.Ready>(repository.initialize()).document
        assertTrue(current.settings.transcription.diarize)
        assertEquals(raw, h.db.recQueries.syncGet(ProcessingSettingsRepository.KEY).executeAsOneOrNull(), "reading preserves original bytes")
        val imported = assertIs<ProcessingSaveResult.Saved>(repository.importJson(raw, current.revision)).document
        assertTrue(imported.settings.transcription.diarize)
    }

    @Test
    fun `first install follows device language and later locale changes preserve saved language`() = runBlocking {
        for ((tag, language) in mapOf("ja-JP" to Language.JA, "zh-Hant-HK" to Language.ZH_TW,
            "pt-BR" to Language.PT, "ar-SA" to Language.AR, "xx" to Language.EN)) {
            val db = recly.core.testing.inMemoryDatabase()
            val repository = ProcessingSettingsRepository(db, recly.core.testing.testDeps(locale = tag))
            assertEquals(language, assertIs<ProcessingSettingsState.Ready>(repository.initialize()).document.settings.transcription.language)
            val reopened = ProcessingSettingsRepository(db, recly.core.testing.testDeps(locale = "en"))
            assertEquals(language, assertIs<ProcessingSettingsState.Ready>(reopened.initialize()).document.settings.transcription.language)
        }
    }

    @Test
    fun `fresh initialization defaults to local only where an on-device runtime is installed`() = runBlocking {
        val installed = ProcessingSettingsRepository(recly.core.testing.inMemoryDatabase(),
            recly.core.testing.testDeps(localTranscription = ReadyEngine))
        assertEquals(TranscriptionMode.LOCAL,
            assertIs<ProcessingSettingsState.Ready>(installed.initialize()).document.settings.transcription.mode)

        val h = Harness()
        // What an older build left behind is not read: a fresh install is a fresh install.
        h.db.recQueries.syncSet("localDoc", "{\"schema\":3,\"workflows\":[]}")
        h.db.recQueries.syncSet("deviceDefaultWorkflowId", "01J9WF00000000000000000000")
        val repository = ProcessingSettingsRepository(h.db, h.deps)
        assertIs<ProcessingSettingsState.NotInitialized>(repository.read())
        val first = repository.initialize()
        assertEquals(TranscriptionMode.OFF, first.document.settings.transcription.mode, "no runtime: local would always fail")
        assertEquals(ProcessingStorage(), first.document.settings.storage)
        assertEquals(1, first.document.revision)
        assertEquals(first, repository.initialize())
        assertEquals(first, repository.observe().first())
    }

    @Test
    fun `concurrent revisions protect edits across repository instances`() = runBlocking {
        val h = Harness()
        val first = ProcessingSettingsRepository(h.db, h.deps)
        val second = ProcessingSettingsRepository(h.db, h.deps)
        val initial = assertIs<ProcessingSettingsState.Ready>(first.initialize())
        val outcomes = listOf(
            async { first.save(initial.document.settings.copy(storage = ProcessingStorage("first")), 1) },
            async { second.save(initial.document.settings.copy(storage = ProcessingStorage("second")), 1) },
        ).awaitAll()
        assertEquals(1, outcomes.count { it is ProcessingSaveResult.Saved })
        assertEquals(1, outcomes.count { it is ProcessingSaveResult.Stale })
        assertEquals(2, assertIs<ProcessingSettingsState.Ready>(first.read()).document.revision)
    }

    @Test
    fun `import stamps a local revision and rejects invalid data without replacing settings`() = runBlocking {
        val h = Harness()
        val repository = ProcessingSettingsRepository(h.db, h.deps)
        repository.initialize()
        val source = document().copy(revision = 999, updatedBy = "other-device")
        val imported = assertIs<ProcessingSaveResult.Saved>(repository.importJson(ProcessingSettingsParser.serialize(source), 1)).document
        assertEquals(2, imported.revision)
        assertEquals(h.deps.device.deviceId, imported.updatedBy)
        val before = repository.exportJson()
        assertIs<ProcessingSaveResult.Invalid>(repository.importJson("{\"schema\":999}", 2))
        assertIs<ProcessingSaveResult.Stale>(repository.importJson(ProcessingSettingsParser.serialize(source), 1))
        assertEquals(before, repository.exportJson())
    }

    @Test
    fun `unreadable current settings are replaced by the defaults when initialized`() = runBlocking {
        val withHook = ProcessingSettingsParser.serialize(document())
            .replace("\"settings\":{", "\"settings\":{\"webhook\":{\"url\":\"https://example.com/hook\"},")
        for (raw in listOf("{bad", "{\"schema\":99,\"future\":true}", withHook)) {
            val h = Harness()
            val repository = ProcessingSettingsRepository(h.db, h.deps)
            h.db.recQueries.syncSet(ProcessingSettingsRepository.KEY, raw)
            assertIs<ProcessingSettingsState.NotInitialized>(repository.read())
            assertIs<ProcessingSaveResult.Unavailable>(repository.save(ProcessingSettings(), 0))
            assertNull(repository.exportJson())
            val fresh = repository.initialize().document
            assertEquals(1, fresh.revision)
            assertEquals(ProcessingSettingsParser.serialize(fresh), h.db.recQueries.syncGet(ProcessingSettingsRepository.KEY).executeAsOneOrNull())
        }
    }

    @Test
    fun `strict settings reject unknown nested fields secrets nulls and unsupported versions`() {
        val raw = ProcessingSettingsParser.serialize(document())
        val cases = listOf(
            raw.replace("\"settings\":{", "\"settings\":{\"unknown\":true,"),
            raw.replace("\"max\":10", "\"max\":10,\"unknown\":true"),
            raw.replace("\"settings\":{", "\"settings\":{\"webhook\":null,"),
            raw.replace("\"settings\":{", "\"settings\":{\"webhook\":{\"url\":\"https://example.com/hook\"},"),
            raw.replace("\"mode\":\"local\"", "\"mode\":\"external\""),
            raw.replace("\"mode\":\"local\"", "\"mode\":\"local\",\"apiKey\":\"not-a-key\""),
            raw.replace("\"schema\":1", "\"schema\":0"),
        )
        cases.forEach { assertIs<ProcessingParseResult.Invalid>(ProcessingSettingsParser.parse(it), it) }
        assertIs<ProcessingParseResult.UnsupportedSchema>(ProcessingSettingsParser.parse(raw.replace("\"schema\":1", "\"schema\":2")))
        assertIs<ProcessingParseResult.Valid>(ProcessingSettingsParser.parse(raw))
    }

    @Test
    fun `provider validation and local speaker settings share existing constraints`() {
        val base = document()
        val cases = listOf(
            ProcessingSettings(transcription = ProcessingTranscription(speakers = Speakers(3, 2))),
            ProcessingSettings(transcription = ProcessingTranscription(mode = TranscriptionMode.EXTERNAL, external = ExternalTranscription("clova", "key"))),
            ProcessingSettings(transcription = ProcessingTranscription(mode = TranscriptionMode.EXTERNAL, external = ExternalTranscription("unknown", "key"))),
            ProcessingSettings(storage = ProcessingStorage("x/{{unknown}}")),
            ProcessingSettings(storage = ProcessingStorage(minDurationSec = -1)),
        )
        cases.forEach { assertTrue(ProcessingSettingsParser.validate(base.copy(settings = it)).isNotEmpty(), it.toString()) }
    }

    @Test
    fun `turning transcription off retains configured external options`() = runBlocking {
        val h = Harness()
        val repository = ProcessingSettingsRepository(h.db, h.deps)
        repository.initialize()
        val external = ExternalTranscription("openai", "my_key", "https://example.com/v1", "configured-model")
        val settings = ProcessingSettings(transcription = ProcessingTranscription(mode = TranscriptionMode.EXTERNAL, external = external))
        assertIs<ProcessingSaveResult.Saved>(repository.save(settings, 1))
        assertIs<ProcessingSaveResult.Saved>(repository.save(settings.copy(transcription = settings.transcription.copy(mode = TranscriptionMode.OFF)), 2))
        val restored = assertIs<ProcessingSettingsState.Ready>(repository.read()).document.settings
        assertEquals(TranscriptionMode.OFF, restored.transcription.mode)
        assertEquals(external, restored.transcription.external)
    }

    @Test
    fun `provider-specific fields do not leak into another provider or a pinned adapter`() {
        val draft = ProcessingDraft.from(ProcessingSettings(transcription = ProcessingTranscription(
            mode = TranscriptionMode.EXTERNAL, external = ExternalTranscription("deepgram", "key", model = "nova-3"))))
        assertTrue(draft.acceptsModel)
        draft.selectProvider("clova")
        assertEquals("", draft.model)
        assertEquals(WorkflowParser.invokeUrlTemplate("clova"), draft.invokeUrl)
        assertFalse(draft.acceptsModel)
        draft.selectProvider("openai")
        assertEquals("", draft.invokeUrl, "an optional endpoint starts empty instead of reusing CLOVA's")
        draft.selectProvider("openai")
        draft.model = "whisper-1"
        draft.selectProvider("openai")
        assertEquals("whisper-1", draft.model, "re-selecting the same provider keeps its fields")
        assertEquals("whisper-1", draft.settings().transcription.external!!.model)
        assertEquals(10, WorkflowParser.STT_PROVIDERS.count { recly.core.transcribe.SttProviders.acceptsModel(it) })
    }

    @Test
    fun `every provider has a display name and keeps its key under its own id`() {
        for (provider in WorkflowParser.STT_PROVIDERS) {
            val shown = recly.core.transcribe.SttProviders.displayName(provider)
            assertTrue(shown != provider && shown.isNotBlank(), provider)
        }
        val draft = ProcessingDraft.from(ProcessingSettings(transcription = ProcessingTranscription(
            mode = TranscriptionMode.EXTERNAL, external = ExternalTranscription("deepgram", "speech_api"))))
        assertEquals("deepgram", draft.secretRef, "a stored shared name is not carried over")
        draft.selectProvider("clova")
        assertEquals("clova", draft.settings().transcription.external!!.secretRef)
    }

    @Test
    fun `a blank minimum length means no minimum`() {
        val draft = ProcessingDraft.from(ProcessingSettings())
        draft.minimumSeconds = " "
        assertEquals(0, draft.settings().storage.minDurationSec)
        draft.minimumSeconds = "x"
        assertEquals(-1, draft.settings().storage.minDurationSec, "garbage is still rejected by validation")
    }

    private object ReadyEngine : recly.core.transcribe.LocalTranscriptionEngine {
        override suspend fun status(language: String) = recly.core.transcribe.LocalEngineInfo(
            recly.core.transcribe.LocalEngineStatus.READY, "fake", "1")
        override suspend fun prepare(language: String) = status(language)
        override suspend fun transcribe(
            request: recly.core.transcribe.LocalTranscriptionRequest,
            progress: recly.core.transcribe.LocalTranscriptionProgress,
        ) = recly.core.transcribe.LocalTranscriptionResult(emptyList())
    }

    private fun document() = ProcessingSettingsDocument(
        revision = 1, updatedAt = "2026-09-24T10:00:00Z", updatedBy = "test-device", settings = ProcessingSettings(),
    )
}
