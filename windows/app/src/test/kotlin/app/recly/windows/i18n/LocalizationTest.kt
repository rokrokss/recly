package app.recly.windows.i18n

import app.recly.windows.FakeSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/07 rules 2 and 3: the choice is stored on this machine, it is applied without a restart, and
 * `system` means whatever the system says at the moment it is asked.
 *
 * The store is faked because the real one is `java.util.prefs`, which is the developer's own
 * profile — a test has no business writing to it.
 */
class LocalizationTest {

    @Test
    fun `the choice is kept and applied at once`() {
        val settings = FakeSettings()
        val localization = Localization(settings) { StringTable.KOREAN }

        assertEquals(StringTable.KOREAN, localization.current.language, "system default is Korean here")

        localization.language = AppLanguage.ENGLISH

        assertEquals(AppLanguage.ENGLISH, settings.language, "the choice is stored")
        assertEquals(StringTable.BASE, localization.strings.value.language)
        assertEquals("Waiting", localization.current[Str.STATUS_WAITING])
    }

    /** docs/07 rule 3: what the flow holds is what everything else reads, and it changes in place. */
    @Test
    fun `the flow carries the new table to whatever is already watching it`() {
        val localization = Localization(FakeSettings(language = AppLanguage.ENGLISH)) { StringTable.BASE }
        val seen = mutableListOf<String>()
        seen += localization.strings.value[Str.TRAY_QUIT]

        localization.language = AppLanguage.KOREAN
        seen += localization.strings.value[Str.TRAY_QUIT]

        assertEquals(listOf("Quit", "종료"), seen)
    }

    /** docs/07 rule 1: `system` is the system's language, and anything but `ko` is English. */
    @Test
    fun `system default follows the system language`() {
        assertEquals(StringTable.KOREAN, Localization(FakeSettings()) { "ko" }.tag)
        assertEquals(StringTable.BASE, Localization(FakeSettings()) { "en" }.tag)
    }

    /**
     * docs/07 rule 2: the picker offers languages and not "follow the system" — a store with
     * nothing in it is shown as the language the app resolved to.
     */
    @Test
    fun `the picker offers all shipped languages without the system sentinel`() {
        assertEquals(
            listOf("en", "ko", "ja", "zh-Hans", "zh-Hant", "es", "fr", "de", "pt", "ar", "hi", "ru"),
            AppLanguage.choices.map { it.first.tag },
        )
    }

    /** What the dropdown marks: the language this window is in, chosen or followed. */
    @Test
    fun `the effective language is the chosen one, or the system's`() {
        assertEquals(AppLanguage.KOREAN, Localization(FakeSettings()) { "ko" }.effective)
        assertEquals(AppLanguage.ENGLISH, Localization(FakeSettings()) { "en" }.effective)
        assertEquals(AppLanguage.JAPANESE, Localization(FakeSettings()) { "ja-JP" }.effective)
        assertEquals(AppLanguage.CHINESE_TRADITIONAL, Localization(FakeSettings()) { "zh-Hant-HK" }.effective)
        assertEquals(AppLanguage.ARABIC, Localization(FakeSettings()) { "ar-SA" }.effective)
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, Localization(FakeSettings()) { "zh-Hans-HK" }.effective)
        assertEquals(
            AppLanguage.ENGLISH,
            Localization(FakeSettings(language = AppLanguage.ENGLISH)) { "ko" }.effective,
        )
    }

    /** A stored value this build does not know — an old release, a hand-edited registry — is `system`. */
    @Test
    fun `an unknown stored tag is the system default`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.of("xx"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.of(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.of(null))
        assertEquals(AppLanguage.KOREAN, AppLanguage.of("ko"))
        AppLanguage.choices.forEach { (language, _) ->
            val localization = Localization(FakeSettings()) { "en" }
            localization.language = language
            assertEquals(language, localization.effective)
            assertEquals(language.tag, localization.strings.value.language)
        }
    }
}
