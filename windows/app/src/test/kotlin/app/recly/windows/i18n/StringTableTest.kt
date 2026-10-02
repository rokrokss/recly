package app.recly.windows.i18n

import app.recly.windows.plain
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/07 rule 9, the completeness half: every key exists in all supported languages and takes the same
 * arguments in each. A key missing from one table falls back to the other silently, which is
 * exactly the bug this catches — the desktop would be Korean everywhere except the one line nobody
 * translated.
 *
 * The files are read from the source tree rather than the classpath, so a key that exists in
 * neither table is caught too (the loader would simply not find it).
 */
class StringTableTest {

    /** The key is derived from the enum name, so two names could collide into one property. */
    @Test
    fun `no two keys derive to the same property name`() {
        assertEquals(Str.entries.size, Str.entries.map { it.key }.toSet().size, "two Str share a key")
    }

    @Test
    fun `all tables hold exactly the keys the enum declares`() {
        val declared = Str.entries.map { it.key }.toSet()

        for (language in LANGUAGES) {
            val table = read(language).keys
            assertEquals(emptySet(), declared - table, "$language: keys with no string")
            assertEquals(emptySet(), table - declared, "$language: strings no Str declares")
        }
    }

    /**
     * A translation whose format arguments do not match the base throws in `String.format` at
     * runtime rather than merely looking wrong, so it is worth its own check.
     */
    @Test
    fun `a translation takes the same format arguments as the base`() {
        val base = read(StringTable.BASE)
        LANGUAGES.forEach { language ->
            val translation = read(language)
            base.forEach { (key, value) ->
                assertEquals(
                    formatArgs(value),
                    formatArgs(translation.getValue(key)),
                    "$language/$key: format arguments differ",
                )
            }
        }
    }

    /** docs/07 rule 1: English is the base language, so Korean in it is a string nobody translated. */
    @Test
    fun `the base table is English`() {
        val korean = read(StringTable.BASE)
            .filterKeys { it !in ALLOWED_HANGUL_KEYS }
            .filterValues { HANGUL.containsMatchIn(it) }

        assertEquals(emptyMap(), korean, "strings_en.properties is the English base")
    }

    /**
     * The cross-shell dictionary (리드 정본), this shell's half: docs/03 "Recordings from other devices" says the
     * same three things on every shell, so the wording is locked here rather than left to drift out
     * of step with the phones' `job_state_*` and RecKit's `stateLabel`. Android's own
     * `CrossShellDictionaryTest` reads these very properties and holds the four of them together.
     */
    @Test
    fun `what another device is doing is worded the same on every shell`() {
        val en = read(StringTable.BASE)
        val ko = read(StringTable.KOREAN)

        assertEquals("Receiving from the watch", en.getValue(Str.STATE_RECEIVING.key))
        assertEquals("워치에서 받는 중", ko.getValue(Str.STATE_RECEIVING.key))
        assertEquals("Uploading on another device", en.getValue(Str.STATE_REMOTE_UPLOADING.key))
        assertEquals("다른 기기에서 업로드 중", ko.getValue(Str.STATE_REMOTE_UPLOADING.key))
        assertEquals("Transcribing on another device", en.getValue(Str.STATE_REMOTE_TRANSCRIBING.key))
        assertEquals("다른 기기에서 전사 중", ko.getValue(Str.STATE_REMOTE_TRANSCRIBING.key))
    }

    /**
     * The speech model's wait and download (2026-09-26), this shell's half of the cross-shell
     * dictionary: the same words on the phones and the Macs, held here so they do not drift.
     */
    @Test
    fun `the speech model wait is worded the same on every shell`() {
        val en = StringTable.of(StringTable.BASE)
        val ko = StringTable.of(StringTable.KOREAN)
        val pinned = listOf(
            Triple(Str.STATE_NEEDS_MODEL, "Waiting for speech model", "음성 모델 대기"),
            Triple(Str.CORE_LOCAL_MODEL_REQUIRED, "The speech recognition model isn't downloaded yet.", "음성 인식 모델을 아직 다운로드하지 않았습니다."),
            Triple(Str.PROCESSING_MODEL_CARD_TITLE, "Transcribe on this device", "이 기기에서 전사하기"),
            Triple(Str.PROCESSING_MODEL_NOT_NOW, "Not now", "나중에"),
            Triple(Str.PROCESSING_PREPARE, "Download model", "모델 다운로드"),
            Triple(Str.PROCESSING_MODEL_RESUME, "Resume download", "이어서 다운로드"),
            Triple(Str.PROCESSING_METERED_TITLE, "Download over a metered connection?", "데이터 요금이 부과되는 연결로 다운로드할까요?"),
            Triple(Str.PROCESSING_MODEL_CANCEL, "Cancel download", "다운로드 취소"),
        )
        pinned.forEach { (key, english, korean) ->
            assertEquals(english, en[key], key.name)
            assertEquals(korean, ko[key].plain(), key.name)
        }
        assertEquals(
            "On-device transcription needs Qwen3-ASR (988 MB), an open-source speech recognition model.",
            en[Str.PROCESSING_MODEL_CARD_BODY, "988 MB"],
        )
        assertEquals("기기 내 전사에는 오픈소스 음성 인식 모델 Qwen3-ASR(988 MB)이 필요합니다.", ko[Str.PROCESSING_MODEL_CARD_BODY, "988 MB"].plain())
        assertEquals("To transcribe on this device, download this model once (988 MB).", en[Str.PROCESSING_MODEL_DOWNLOAD, "988 MB"])
        assertEquals("이 기기에서 전사하려면 이 모델(988 MB)을 한 번 다운로드해야 합니다.", ko[Str.PROCESSING_MODEL_DOWNLOAD, "988 MB"].plain())
        assertEquals("The model is 988 MB.", en[Str.PROCESSING_MODEL_SIZE, "988 MB"])
        assertEquals("모델은 988 MB입니다.", ko[Str.PROCESSING_MODEL_SIZE, "988 MB"].plain())
        assertEquals("Downloading model… 42%", en[Str.PROCESSING_MODEL_DOWNLOADING, 42])
        assertEquals("모델 다운로드 중… 42%", ko[Str.PROCESSING_MODEL_DOWNLOADING, 42].plain())
        assertEquals("412 MB of 988 MB", en[Str.PROCESSING_MODEL_BYTES, "412 MB", "988 MB"])
        assertEquals("988 MB 중 412 MB", ko[Str.PROCESSING_MODEL_BYTES, "412 MB", "988 MB"].plain())
    }

    /** English counts say the number after the thing counted, never "recording(s)". */
    @Test
    fun `English counts have no parenthesised plural`() {
        val en = read(StringTable.BASE)
        // "(s)" there is seconds, not a plural.
        val plurals = en.filterKeys { it != Str.FIELD_MIN_DURATION.key }.filterValues { "(s)" in it }
        assertEquals(emptyMap(), plurals)
        val strings = StringTable.of(StringTable.BASE)
        assertEquals("Recordings waiting: 2", strings[Str.ALERT_WAITING, 2])
        assertEquals("Recordings not yet in Drive, kept on this PC: 1", strings[Str.DISCONNECT_UNUPLOADED, 1])
        assertEquals("Disconnected. Recordings deleted from this PC: 4", strings[Str.DISCONNECT_DELETED, 4])
        assertEquals(
            "Google access was revoked. Recordings still running and kept: 1 — disconnect again once they have finished.",
            strings[Str.DISCONNECT_BUSY, 1],
        )
    }

    /**
     * Korean breaks only at its spaces: the table holds each word together with WORD JOINERs, so
     * Skia — which would otherwise break between any two syllables — cannot end a line inside one.
     */
    @Test
    fun `Korean words are held together and every other table is as written`() {
        val ko = StringTable.of(StringTable.KOREAN)[Str.CORE_LOCAL_MODEL_REQUIRED]
        assertEquals("음성 인식 모델을 아직 다운로드하지 않았습니다.", ko.plain())
        // The only places left to break are the spaces.
        ko.split(' ').forEach { word ->
            word.windowed(2).forEach { pair -> assertTrue(WORD_JOINER in pair, "a break inside \"${word.plain()}\"") }
        }
        assertEquals("않\u2060았\u2060습\u2060니\u2060다\u2060.", ko.split(' ').last())
        val en = StringTable.of(StringTable.BASE)[Str.CORE_LOCAL_MODEL_REQUIRED]
        assertFalse(WORD_JOINER in en)
        assertEquals("Drive\u2060에", joinKoreanWords("Drive에"), "a particle stays on the word it follows")
        assertEquals("모\u2060델\u2060(%1\$s)\u2060을", joinKoreanWords("모델(%1\$s)을"), "an argument's place stays on its word")
    }

    /** What the user wrote goes in as it is: only the app's own sentence is joined. */
    @Test
    fun `an argument is put in untouched`() {
        val title = "주간 회의"
        val sentence = StringTable.of(StringTable.KOREAN)[Str.DELETE_TITLE, title]
        assertTrue(title in sentence, "the title was joined")
    }

    /** The loader reads UTF-8 (`Properties.load(InputStream)` would not) and formats positionally. */
    @Test
    fun `a loaded table formats its arguments in its own language`() {
        assertEquals("Recordings waiting: 2", StringTable.of(StringTable.BASE)[Str.ALERT_WAITING, 2])
        assertEquals("녹음 2건이 기다리는 중입니다.", StringTable.of(StringTable.KOREAN)[Str.ALERT_WAITING, 2].plain())
    }

    /** docs/07 rule 1: regional tags resolve to their supported language, with English as the fallback. */
    @Test
    fun `supported regions resolve and unknown languages use the base table`() {
        assertEquals("ja", StringTable.of("ja-JP").language)
        assertEquals("zh-Hant", StringTable.of("zh-TW").language)
        assertEquals(StringTable.BASE, StringTable.of("xx").language)
        assertEquals(StringTable.BASE, StringTable.of("").language)
        assertEquals(StringTable.KOREAN, StringTable.of("ko").language)
    }

    private fun read(language: String): Map<String, String> {
        val file = File(RESOURCES, "strings_$language.properties")
        assertTrue(file.isFile, "missing ${file.path}")
        val properties = Properties()
        file.reader(Charsets.UTF_8).use { properties.load(it) }
        return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
    }

    private fun formatArgs(value: String): Set<String> = FORMAT_ARG.findAll(value).map { it.value }.toSet()

    private companion object {
        /** Tests run with `windows/app` as the working directory (Gradle's default). */
        val RESOURCES = File("src/main/resources/i18n")

        val LANGUAGES = AppLanguage.choices.map { it.first.tag }

        val FORMAT_ARG = Regex("%\\d+\\$[a-zA-Z]")

        val HANGUL = Regex("[가-힣]")

        /** The one thing an English base says in Korean: the name of the Korean language. */
        val ALLOWED_HANGUL_KEYS = setOf(Str.LANGUAGE_KO.key)
    }
}
