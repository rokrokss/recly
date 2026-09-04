package app.recly.android.settings

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** `LocaleManager` is a system service the JVM cannot drive; this is what the app writes through. */
private class FakeLocaleStore(private var tag: String = "") : LocaleStore {
    val applied: MutableList<String> = mutableListOf()

    override fun current(): String = tag

    override fun apply(tag: String) {
        applied += tag
        this.tag = tag
    }
}

/** The notification and the widget, which on a device are two calls into Android. */
private class FakeSurfaces(private val store: FakeLocaleStore) : LocalizedSurfaces {
    /** What the store held each time a redraw was asked for — the order is the whole point. */
    val refreshedAt: MutableList<String> = mutableListOf()

    override fun refresh() {
        refreshedAt += store.current()
    }
}

/**
 * docs/07 rule 2: three answers, stored by the platform. The point of the test is the round trip —
 * what the screen shows after a choice has to be the choice, because the activity is recreated in
 * between and the state is rebuilt from the store.
 */
class LanguageSettingTest {

    private val store = FakeLocaleStore()
    private val surfaces = FakeSurfaces(store)
    private val setting = LanguageSetting(store, surfaces)

    @Test
    fun `nothing chosen is the system default`() {
        assertEquals(AppLanguage.SYSTEM, setting.current())
    }

    @Test
    fun `a chosen language is written to the store and read back`() {
        setting.select(AppLanguage.KOREAN)

        assertEquals(listOf("ko"), store.applied)
        assertEquals(AppLanguage.KOREAN, setting.current())

        setting.select(AppLanguage.ENGLISH)

        assertEquals(listOf("ko", "en"), store.applied)
        assertEquals(AppLanguage.ENGLISH, setting.current())
    }

    /** Going back to the system default clears the choice rather than writing a third language. */
    @Test
    fun `the system default is the empty tag`() {
        setting.select(AppLanguage.KOREAN)
        setting.select(AppLanguage.SYSTEM)

        assertEquals(listOf("ko", ""), store.applied)
        assertEquals(AppLanguage.SYSTEM, setting.current())
    }

    /**
     * docs/07 rule 3: the activities are recreated for us, the ongoing notification and the widget
     * are not — and they read the locale that was just applied, so the order matters.
     */
    @Test
    fun `everything drawn outside the activity is redrawn, after the locale is applied`() {
        setting.select(AppLanguage.KOREAN)

        assertEquals(listOf("ko"), surfaces.refreshedAt)

        setting.select(AppLanguage.SYSTEM)

        assertEquals(listOf("ko", ""), surfaces.refreshedAt)
    }

    /**
     * docs/07 rule 2: the picker offers languages and not "follow the system" — a store with
     * nothing in it is shown as the language the app resolved to.
     */
    @Test
    fun `the picker offers the two languages and not the system default`() {
        assertEquals(listOf(AppLanguage.ENGLISH, AppLanguage.KOREAN), AppLanguage.choices)
    }

    /** What the row says and the dialog marks: the locale the app's own words were resolved in. */
    @Test
    fun `the effective language is Korean only for a Korean locale`() {
        assertEquals(AppLanguage.KOREAN, AppLanguage.effective(Locale.KOREAN))
        assertEquals(AppLanguage.KOREAN, AppLanguage.effective(Locale.KOREA))
        listOf(Locale.ENGLISH, Locale.UK, Locale.JAPANESE).forEach {
            assertEquals(AppLanguage.ENGLISH, AppLanguage.effective(it), "locale '$it'")
        }
    }

    /** A tag this app does not offer — another language, or none — reads as the system default. */
    @Test
    fun `only the two shipped tags are recognised`() {
        assertEquals(AppLanguage.KOREAN, AppLanguage.of("ko"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.of("en"))
        listOf("", "ja", "en-GB").forEach {
            assertEquals(AppLanguage.SYSTEM, AppLanguage.of(it), "tag '$it'")
        }
    }
}
