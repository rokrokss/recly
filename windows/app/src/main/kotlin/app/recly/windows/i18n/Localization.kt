package app.recly.windows.i18n

import app.recly.windows.core.Host
import app.recly.windows.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * docs/07 rule 2: the three answers the language setting offers. [tag] is what is stored; the empty
 * one is "whatever the system says", which is [Host.language] (docs/07 rule 1 — `ko` or English).
 */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    KOREAN(StringTable.KOREAN),
    ENGLISH(StringTable.BASE),
    ;

    companion object {
        /** Anything the store does not recognise — another language, a region — is [SYSTEM]. */
        fun of(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag.isNotEmpty() && it.tag == tag } ?: SYSTEM

        /**
         * docs/07 rule 2: what the picker offers, each under its own name and in the order of those
         * names. [SYSTEM] is not one of them — it is the store's "nothing chosen", and what the
         * picker then shows as chosen is [Localization.effective]. The label lives here rather than
         * on the entries because only these two are ever drawn.
         */
        val choices: List<Pair<AppLanguage, Str>> =
            listOf(ENGLISH to Str.LANGUAGE_EN, KOREAN to Str.LANGUAGE_KO)
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

    /** `en` or `ko` — what [recly.core.platform.CoreDeps.locale] takes (docs/07 §6). */
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
