package app.recly.windows.i18n

import app.recly.windows.core.Host
import app.recly.windows.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * docs/07 rule 2: the supported app languages. [tag] is what is stored; the empty
 * one is "whatever the system says", which is [Host.language] (docs/07 rule 1).
 */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    KOREAN(StringTable.KOREAN),
    ENGLISH(StringTable.BASE),
    JAPANESE("ja"),
    CHINESE_SIMPLIFIED("zh-Hans"),
    CHINESE_TRADITIONAL("zh-Hant"),
    SPANISH("es"),
    FRENCH("fr"),
    GERMAN("de"),
    PORTUGUESE("pt"),
    ARABIC("ar"),
    HINDI("hi"),
    RUSSIAN("ru"),

    ;

    companion object {
        /** Regional tags resolve to their supported language; unknown tags are [SYSTEM]. */
        fun of(tag: String?): AppLanguage {
            val normalized = tag.orEmpty().replace('_', '-').lowercase()
            if (normalized.substringBefore('-') == "zh") {
                val parts = normalized.split('-')
                return when {
                    "hans" in parts -> CHINESE_SIMPLIFIED
                    "hant" in parts || parts.any { it in listOf("tw", "hk", "mo") } -> CHINESE_TRADITIONAL
                    else -> CHINESE_SIMPLIFIED
                }
            }
            return entries.firstOrNull { it.tag.isNotEmpty() && (it.tag.lowercase() == normalized || it.tag == normalized.substringBefore('-')) } ?: SYSTEM
        }

        /**
         * docs/07 rule 2: what the picker offers, each under its own name and in the order of those
         * names. [SYSTEM] is not one of them — it is the store's "nothing chosen", and what the
         * picker then shows as chosen is [Localization.effective]. The label lives here rather than
         * on the entries because the system sentinel is never drawn.
         */
        val choices: List<Pair<AppLanguage, Str>> =
            listOf(ENGLISH to Str.LANGUAGE_EN, KOREAN to Str.LANGUAGE_KO, JAPANESE to Str.LANGUAGE_JA, CHINESE_SIMPLIFIED to Str.LANGUAGE_ZH_HANS, CHINESE_TRADITIONAL to Str.LANGUAGE_ZH_HANT, SPANISH to Str.LANGUAGE_ES, FRENCH to Str.LANGUAGE_FR, GERMAN to Str.LANGUAGE_DE, PORTUGUESE to Str.LANGUAGE_PT, ARABIC to Str.LANGUAGE_AR, HINDI to Str.LANGUAGE_HI, RUSSIAN to Str.LANGUAGE_RU)
    }
}

/**
 * The app's language, and the table that goes with it. One per process: the tray, every window and
 * the notifications all have to be in the same language at the same moment (docs/07 rule 3), and
 * this is the single thing they read it from.
 *
 * A [StateFlow] rather than a Compose state because most of what this app says is said from outside
 * a composition — an AWT balloon, the loopback sign-in page — and those read [current] directly;
 * the Compose trees collect the flow and recompose, which is what rebuilds the tray menu.
 */
class Localization(
    /** The same `java.util.prefs` node the shell's other switches live in. */
    val settings: Settings = Settings.create(),
    /** The system's language, read afresh on every change so [AppLanguage.SYSTEM] stays honest. */
    private val systemLanguage: () -> String = Host::language,
) {
    private val state = MutableStateFlow(table(settings.language))

    val strings: StateFlow<Strings> = state.asStateFlow()

    /** For everything that is not a composition: the table as it is right now. */
    val current: Strings get() = state.value

    /** The full language tag supplied to [recly.core.platform.CoreDeps.locale] (docs/07 §6). */
    val tag: String get() = current.language

    /**
     * The language the app is actually in, which is what the picker shows as chosen: the stored
     * choice, or — with nothing stored — the system's narrowed to a language this app has. Read off
     * the table in hand rather than worked out again: the table *is* the narrowing (docs/07 rule 1).
     */
    val effective: AppLanguage get() = AppLanguage.of(tag)

    var language: AppLanguage
        get() = settings.language
        set(value) {
            settings.language = value
            state.value = table(value)
        }

    private fun table(language: AppLanguage): Strings =
        StringTable.of(language.tag.ifEmpty { systemLanguage() })
}
