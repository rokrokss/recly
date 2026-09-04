package app.recly.windows.ui

import app.recly.windows.FailingSettings
import app.recly.windows.FakeSettings
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.message
import app.recly.windows.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/03 "연결 해제" · docs/06, the two shell rules a disconnect keeps outside the guard itself: the
 * account slot belongs to it until it has finished, and a store that would not take a write is said
 * rather than dropped. The phone's `MainViewModel` and the Mac's `MenuModel` keep both.
 *
 * A shell that has never been loaded: no core, no disk, and the settings store faked — the real one
 * is `java.util.prefs`, which is the developer's own profile.
 */
class ShellDisconnectTest {

    /**
     * docs/06: the other end of the sign-in rule. A plain sign-out after REVOKE_PENDING would delete
     * the refresh token the retry reads to tell "revoke again" from "already revoked", and the grant
     * would be left standing with no debt written.
     */
    @Test
    fun `with no core open a sign-out says nothing, whatever the phase`() {
        // The refusal itself is DisconnectGuard.signInBlocker's (tested there); a shell whose core
        // never opened has no sign-out to refuse, and its status line keeps saying why.
        val phases = listOf(DisconnectPhase.REVOKE_PENDING, DisconnectPhase.REVOKED_CLEANUP_OWED)

        for (phase in phases) {
            val model = shell(FakeSettings(disconnectPhase = phase))

            model.signOut()

            assertEquals(
                Str.STATUS_OPENING.message(),
                model.status,
                "a sign-out with no core overwrote the status over $phase",
            )
        }
    }

    /** And nothing owed is nothing in the way: the refusal is the phase's, not the button's. */
    @Test
    fun `signing out is not refused when no disconnect is owed`() {
        val model = shell(FakeSettings())

        model.signOut()

        assertEquals(Str.STATUS_OPENING.message(), model.status, "a sign-out was refused with nothing owed")
    }

    /**
     * docs/03: the debt is the user's own word, and a store that would not keep it has to say so —
     * the row is back on the next launch either way, and a line that said nothing would leave them
     * pressing a button that does nothing.
     */
    @Test
    fun `a debt the store would not clear is said rather than dropped`() {
        val settings = FailingSettings(FakeSettings(revokeDebt = true))
        val model = shell(settings)

        model.revokeDebtSettled()

        assertEquals(Str.DISCONNECT_SAVE_FAILED.message(), model.status)
        assertTrue(model.revokeDebt, "the row went away over a debt that is still on disk")
        assertTrue(settings.revokeDebt, "the store was left saying something it had refused")
    }

    @Test
    fun `a debt the store took is cleared`() {
        val settings = FakeSettings(revokeDebt = true)
        val model = shell(settings)

        model.revokeDebtSettled()

        assertFalse(model.revokeDebt)
        assertFalse(settings.revokeDebt)
        assertEquals(Str.STATUS_OPENING.message(), model.status, "clearing the debt said something")
    }

    private fun shell(settings: Settings) = ShellModel(
        localization = Localization(settings) { StringTable.BASE },
    )
}
