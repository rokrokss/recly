package app.recly.android.ui

import androidx.lifecycle.SavedStateHandle
import app.recly.android.auth.SignInResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the settings screen does with [SignInResult.NoAccount]: open the system add-account screen
 * once, and retry sign-in once on the way back. The `MainViewModel` wiring is three lines around
 * this controller (`onNoAccount` / `consumeAddAccountLaunch` / `onAddAccountResult`), so this is
 * the state machine that decides it.
 */
class AddAccountControllerTest {

    @Test
    fun noAccountAsksTheActivityToOpenTheAddAccountScreen() {
        val controller = AddAccountController(SavedStateHandle())
        assertFalse(controller.request.value, "nothing is asked for before a sign-in fails")

        controller.onNoAccount()

        assertTrue(controller.request.value)
        assertTrue(controller.consumeLaunch(), "the attached activity opens the screen")
        // Rotation: a recreated activity collects the same request and must not open a second one.
        assertFalse(controller.consumeLaunch())
    }

    @Test
    fun comingBackRetriesSignInExactlyOnce() {
        val controller = AddAccountController(SavedStateHandle())
        controller.onNoAccount()
        controller.consumeLaunch()

        assertTrue(controller.onReturned(), "the account the user may have added deserves a retry")
        assertFalse(controller.request.value, "the request is finished")

        // The retry found nothing either: telling the user is all that is left. Asking again would
        // be a loop between the app and Settings.
        controller.onNoAccount()
        assertFalse(controller.request.value)
        assertFalse(controller.consumeLaunch())
    }

    /**
     * Adding a Google account is minutes in another app, and the system is free to kill this
     * process behind it. The `ActivityResultRegistry` still delivers the result to the recreated
     * activity — so the retry has to survive the same death, or the one thing the user was sent
     * away to do is dropped.
     */
    @Test
    fun theRetrySurvivesProcessDeathBehindTheAddAccountScreen() {
        val handle = SavedStateHandle()
        val died = AddAccountController(handle)
        died.onNoAccount()
        assertTrue(died.consumeLaunch(), "the screen is up when the process is killed")

        val restored = AddAccountController(restore(handle))

        assertTrue(restored.request.value, "the pending request comes back")
        assertFalse(restored.consumeLaunch(), "the screen is already up — do not open a second one")
        assertTrue(restored.onReturned(), "the delivered result still triggers the one retry")
        assertFalse(restored.request.value)

        // And the bound holds across the death too: still exactly one retry, not one per process.
        restored.onNoAccount()
        assertFalse(restored.request.value)
    }

    /**
     * The guard covers one trip to Settings, not the rest of the process's life. A user who backed
     * out of the add-account screen — or who removed the account again later — presses Sign in to be
     * sent there, and `MainViewModel.signIn` is the tap that says so.
     */
    @Test
    fun pressingSignInAgainCanReopenTheAddAccountScreen() {
        val controller = AddAccountController(SavedStateHandle())

        // First tap: no account, Settings opens, and the return spends the automatic retry.
        controller.onUserSignIn()
        controller.onNoAccount()
        assertTrue(controller.consumeLaunch())
        assertTrue(controller.onReturned())

        // The retry found nothing either. That must not open Settings a second time by itself.
        controller.onNoAccount()
        assertFalse(controller.request.value, "the automatic retry must not loop back to Settings")

        // The user presses Sign in themselves, and the whole thing is available again.
        controller.onUserSignIn()
        controller.onNoAccount()
        assertTrue(controller.request.value)
        assertTrue(controller.consumeLaunch(), "Settings opens once more")
        assertFalse(controller.consumeLaunch(), "but still only once")
    }

    @Test
    fun aStrayResultWithoutARequestRetriesNothing() {
        assertFalse(AddAccountController(SavedStateHandle()).onReturned())
    }

    /**
     * Process death and recreation. The real round trip goes through a `Bundle`, which is a stub
     * off-device; copying the handle's contents into a fresh one exercises the same thing that
     * matters here — a controller built from nothing but what was saved.
     */
    private fun restore(handle: SavedStateHandle) =
        SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
}
