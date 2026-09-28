@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import app.recly.android.R
import app.recly.android.auth.AuthorizeResult
import app.recly.android.auth.SignInResult
import app.recly.android.core.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * What the Drive row says after the user tried to connect, as the iPhone says it: nothing for a
 * closed picker or consent screen, nothing for a success — the row turning into the account is the
 * answer — and for a failure only that Drive could not be connected, never Play Services' own words.
 */
class DriveConnectionNoteTest {

    @Test
    fun `a closed picker or consent screen says nothing`() {
        assertNull(signInNote(SignInResult.Cancelled))
        assertNull(authorizeNote(AuthorizeResult.Cancelled))
    }

    @Test
    fun `a success says nothing`() {
        assertNull(signInNote(SignInResult.SignedIn("a@example.test")))
        assertNull(authorizeNote(AuthorizeResult.Granted("token", Instant.fromEpochMilliseconds(0))))
    }

    @Test
    fun `a failure says Drive could not be connected, and not why`() {
        val failed = UiMessage.Res(R.string.auth_connect_failed)
        assertEquals(failed, signInNote(SignInResult.Failed("java.io.IOException: 16: Cannot find a matching credential")))
        assertEquals(failed, authorizeNote(AuthorizeResult.Failed("authorization returned no access token")))
    }

    @Test
    fun `no Google account says how to get one`() {
        assertEquals(UiMessage.Res(R.string.auth_add_account), signInNote(SignInResult.NoAccount))
    }

    /** docs/06 Android: identity is not authorization, so an account with a closed consent is not connected. */
    @Test
    fun `the row is connected only with the account and its grant`() {
        assertTrue(MainUiState(email = "a@example.test", driveGranted = true).driveConnected)
        assertFalse(MainUiState(email = "a@example.test", driveGranted = false).driveConnected)
        assertFalse(MainUiState(email = null, driveGranted = true).driveConnected)
    }
}
