package app.recly.android.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.glance.appwidget.updateAll
import app.recly.android.entry.RecWidget
import app.recly.android.work.JobAlertNotifier
import app.recly.recording.RecorderService
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * docs/07 rule 2: the three answers the language setting offers. [tag] is the language tag the
 * platform stores; the empty one is "whatever the system says".
 */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    KOREAN("ko"),
    ENGLISH("en"),
    ;

    companion object {
        /** Anything the store does not recognise — another language, a region — is [SYSTEM]. */
        fun of(tag: String): AppLanguage = entries.firstOrNull { it.tag.isNotEmpty() && it.tag == tag } ?: SYSTEM

        /**
         * docs/07 rule 2: what the picker offers, in the order of the names it draws them under.
         * [SYSTEM] is not one of them — it is the store's "nothing chosen", and what the picker
         * then shows as chosen is [effective].
         */
        val choices: List<AppLanguage> = listOf(ENGLISH, KOREAN)

        /**
         * The language the app is actually in. [locale] is the one the app's resources resolved to
         * — `Configuration.getLocales()`, which already carries whatever per-app override
         * `LocaleManager` applied — and anything that is not Korean is the base language the
         * strings are written in (docs/07 rule 1).
         */
        fun effective(locale: Locale): AppLanguage =
            if (locale.language == KOREAN.tag) KOREAN else ENGLISH
    }
}

/**
 * Where the choice lives. Behind an interface because the platform service is not something the JVM
 * test can drive, and because the persistence is not ours: the system stores the per-app locale,
 * which is what makes the choice survive a restart and appear in the system's own per-app language
 * screen (docs/07 rule 2).
 */
interface LocaleStore {
    fun current(): String

    fun apply(tag: String)
}

/**
 * `LocaleManager` rather than `AppCompatDelegate.setApplicationLocales`: on API 33+ — and `minSdk`
 * here is 34 — the AppCompat call does nothing but forward to this same service, and it finds it by
 * walking the app's `AppCompatDelegate`s. This app's screens are `ComponentActivity` + Compose, so
 * there are none and the AppCompat call would silently do nothing. AppCompat's `autoStoreLocales`
 * backport is for API < 33 and has nothing to store here.
 */
class SystemLocaleStore(context: Context) : LocaleStore {

    private val manager = context.getSystemService(LocaleManager::class.java)

    override fun current(): String = manager.applicationLocales.toLanguageTags().substringBefore('-')

    /** An empty tag clears the override, which is what "system default" means. */
    override fun apply(tag: String) {
        manager.applicationLocales = LocaleList.forLanguageTags(tag)
    }
}

/**
 * Everything this app draws that the system will not redraw for us. Setting a locale recreates the
 * activities, so the screens take care of themselves; the ongoing recording notification and the
 * home widget are painted once and then left alone, and would sit in the old language until
 * something else happened to them (docs/07 rule 3).
 */
fun interface LocalizedSurfaces {
    fun refresh()

    companion object {
        /** For a caller with nothing outside the activity — the tests, and nothing else. */
        val None: LocalizedSurfaces = LocalizedSurfaces {}
    }
}

/**
 * The recorder's notification, the job notifications and the home widget — everything this app
 * draws outside an activity, and so everything the platform will not redraw on a locale change.
 */
class AppSurfaces(private val context: Context) : LocalizedSurfaces {
    override fun refresh() {
        RecorderService.refreshNotification(context)
        // docs/10: posted once and then left alone, so they would keep the old language.
        JobAlertNotifier.refresh(context)
        // Glance renders in the launcher's process off a snapshot of ours; nothing but a push
        // makes it look again. `updateAll` is a no-op when the widget is not placed.
        CoroutineScope(Dispatchers.Main).launch { runCatching { RecWidget().updateAll(context) } }
    }
}

/**
 * The language setting itself. Setting it recreates the activities, so this holds no state of its
 * own: what the screen shows is always what the store says.
 */
class LanguageSetting(
    private val store: LocaleStore,
    private val surfaces: LocalizedSurfaces = LocalizedSurfaces.None,
) {

    fun current(): AppLanguage = AppLanguage.of(store.current())

    /** The store first: what the surfaces redraw themselves from is the locale it just applied. */
    fun select(language: AppLanguage) {
        store.apply(language.tag)
        surfaces.refresh()
    }
}
