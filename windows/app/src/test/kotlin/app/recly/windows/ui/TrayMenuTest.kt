package app.recly.windows.ui

import app.recly.windows.FakeSettings
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.StringTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/07 rule 3 on the one surface that is not Compose's to redraw: choosing a language has to
 * rebuild the AWT tray menu, not only the windows.
 *
 * There is no tray here — [trayMenu] is the seam, and `Main.kt` does nothing with its answer but
 * turn each entry into a menu item. What the tray would show is therefore exactly this list. Since
 * docs/09 화면 원칙 6 the list is the fallback rather than the UI: the sign-in, the way in to the
 * popup window, start/stop, and quit.
 */
class TrayMenuTest {

    @Test
    fun `choosing a language rebuilds every label in the menu`() {
        val model = shell()

        val english = labels(model)
        model.selectLanguage(AppLanguage.KOREAN)
        val korean = labels(model)

        assertEquals(
            listOf("Opening", "Sign in with Google", "Open Recly", "Start recording", "Quit"),
            english,
        )
        assertEquals(
            listOf("여는 중", "Google 로그인", "Recly 열기", "녹음 시작", "종료"),
            korean,
        )
    }

    /** The rebuild is only the words: what each line does and whether it can be clicked is the same. */
    @Test
    fun `the shape of the menu does not depend on the language`() {
        val model = shell()

        val english = trayMenu(model, model.localization.current, quit = {})
        model.selectLanguage(AppLanguage.KOREAN)
        val korean = trayMenu(model, model.localization.current, quit = {})

        assertEquals(english.map { it::class }, korean.map { it::class })
        assertEquals(
            english.filterIsInstance<TrayEntry.Item>().map { it.enabled },
            korean.filterIsInstance<TrayEntry.Item>().map { it.enabled },
        )
    }

    /** The status line is a label, not a button; nothing can be started before [ShellModel.load]. */
    @Test
    fun `the status line and an unloaded shell are both unclickable`() {
        val items = trayMenu(shell(), StringTable.of(StringTable.BASE), quit = {})
            .filterIsInstance<TrayEntry.Item>()

        assertFalse(items.first().enabled)
        assertFalse(items.single { it.label == "Start recording" }.enabled)
        assertTrue(items.single { it.label == "Quit" }.enabled)
    }

    /**
     * docs/06: a job parked in NEEDS_AUTH waits for a sign-in, and the popup is the only other place
     * that offers one. On a machine where that window will not open, this item is the whole of what
     * the user has, so a signed-out shell keeps it in the fallback.
     */
    @Test
    fun `the fallback menu still offers the sign-in a parked job waits for`() {
        val items = trayMenu(shell(), StringTable.of(StringTable.BASE), quit = {})
            .filterIsInstance<TrayEntry.Item>()

        val signIn = items.single { it.label == "Sign in with Google" }

        assertTrue(signIn.enabled)
        // A shell with no core signs nobody in; what this says is that the item is wired to the
        // model at all rather than to the menu's own quit.
        signIn.onClick()
    }

    @Test
    fun `the last item is the quit the application hands in`() {
        var quit = 0
        val items = trayMenu(shell(), StringTable.of(StringTable.BASE), quit = { quit++ })
            .filterIsInstance<TrayEntry.Item>()

        items.last().onClick()

        assertEquals(1, quit)
    }

    private fun labels(model: ShellModel): List<String> =
        trayMenu(model, model.localization.current, quit = {})
            .filterIsInstance<TrayEntry.Item>()
            .map { it.label }

    /**
     * A shell that has never been loaded: no core, no disk, and every switch at its default — which
     * is all the menu needs, and all a test may touch (the real settings store is the developer's
     * own `java.util.prefs`).
     */
    private fun shell() = ShellModel(
        localization = Localization(FakeSettings(language = AppLanguage.ENGLISH)) { StringTable.BASE },
    )
}
