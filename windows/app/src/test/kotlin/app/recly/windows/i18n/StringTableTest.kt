package app.recly.windows.i18n

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
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
     * The cross-shell dictionary (리드 정본), this shell's half: docs/03 "다른 기기의 녹음" says the
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

    /** The loader reads UTF-8 (`Properties.load(InputStream)` would not) and formats positionally. */
    @Test
    fun `a loaded table formats its arguments in its own language`() {
        assertEquals("Deferred 2", StringTable.of(StringTable.BASE)[Str.STATUS_DEFERRED, 2])
        assertEquals("보류 2", StringTable.of(StringTable.KOREAN)[Str.STATUS_DEFERRED, 2])
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
