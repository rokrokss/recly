package app.recly.android.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import recly.core.model.Language
import recly.core.processing.ProcessingDraft
import recly.core.processing.ProcessingSettings
import recly.core.processing.TranscriptionMode
import recly.core.processing.selectTranscriptionMode

/**
 * The two rules the processing panel keeps outside the screen: what switching to on-device does to a
 * language the model cannot take (iPhone's `selectAppleTranscriptionMode`), and what an import of a
 * file too large to be settings says (iPhone's `pick`).
 */
class ProcessingDraftRulesTest {

    @Test
    fun `on-device from Automatic takes the device's language`() {
        val draft = draft(Language.AUTO)

        draft.selectTranscriptionMode(TranscriptionMode.LOCAL, "ja-JP")

        assertEquals(TranscriptionMode.LOCAL, draft.mode)
        assertEquals(Language.JA, draft.language, "Save would otherwise stay disabled")
    }

    @Test
    fun `on-device from Korean and English reads the script of a Chinese device`() {
        val draft = draft(Language.KO_EN)

        draft.selectTranscriptionMode(TranscriptionMode.LOCAL, "zh-Hant-TW")

        assertEquals(Language.ZH_TW, draft.language)
    }

    /** Qwen3-ASR has no Ukrainian: the device's language is only taken when the model has it. */
    @Test
    fun `a device language the model lacks falls back to English`() {
        val draft = draft(Language.AUTO)

        draft.selectTranscriptionMode(TranscriptionMode.LOCAL, "uk-UA")

        assertEquals(Language.EN, draft.language)
    }

    /** A choice the user made stays, with the line that says the model lacks it — as on the iPhone. */
    @Test
    fun `an explicit language is left alone, even one the model lacks`() {
        val draft = draft(Language.UK)

        draft.selectTranscriptionMode(TranscriptionMode.LOCAL, "ko-KR")

        assertEquals(Language.UK, draft.language)
    }

    @Test
    fun `the other methods keep Automatic`() {
        listOf(TranscriptionMode.EXTERNAL, TranscriptionMode.OFF).forEach { mode ->
            val draft = draft(Language.AUTO, mode = TranscriptionMode.LOCAL)

            draft.selectTranscriptionMode(mode, "ko-KR")

            assertEquals(mode, draft.mode)
            assertEquals(Language.AUTO, draft.language, "$mode")
        }
    }

    @Test
    fun `a readable settings file is offered as a draft`() {
        assertNotNull(importedSettings(example().toByteArray()))
    }

    /** Not "Failed: Failed requirement." — the same sentence as any other file this version cannot read. */
    @Test
    fun `a file over the limit is unreadable, however valid its start`() {
        val oversized = example() + " ".repeat(IMPORT_LIMIT)

        assertNull(importedSettings(oversized.toByteArray()))
    }

    @Test
    fun `a file that is not settings is unreadable`() {
        assertNull(importedSettings("not json".toByteArray()))
    }

    private fun draft(language: Language, mode: TranscriptionMode = TranscriptionMode.EXTERNAL): ProcessingDraft =
        ProcessingDraft.from(ProcessingSettings.defaults()).apply {
            this.mode = mode
            this.language = language
        }

    /** The contract's own example (spec/examples); unit tests run with `android/app` as the working directory. */
    private fun example(): String = File("../../spec/examples/recording-settings.json").readText()
}
