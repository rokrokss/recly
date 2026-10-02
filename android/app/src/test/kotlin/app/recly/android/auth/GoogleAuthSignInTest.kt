@file:OptIn(ExperimentalTime::class)

package app.recly.android.auth

import android.app.Activity
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import recly.core.platform.Clock
import recly.core.platform.Logger
import recly.core.platform.SecureStore

/**
 * The docs/06 Connect Drive sign-in. The Play Services round trip is behind [CredentialRequester],
 * so what is under test here is what each outcome of the button flow tells the UI.
 */
class GoogleAuthSignInTest {

    @Test
    fun aConnectTapSignsInWithTheButtonFlow() = runTest {
        val requester = FakeRequester(Answer.Email("a@example.com"))
        val store = FakeSecureStore()

        assertEquals(SignInResult.SignedIn("a@example.com"), auth(requester, store).signIn(activity))

        assertEquals(1, requester.asked)
        assertEquals("a@example.com", store.get("account", "email")?.decodeToString())
        assertEquals(emptyList(), logger.events)
    }

    @Test
    fun aDeviceWithNoGoogleAccountAsksForOne() = runTest {
        val requester = FakeRequester(Answer.NoCredential)
        val store = FakeSecureStore()

        assertEquals(SignInResult.NoAccount, auth(requester, store).signIn(activity))
        assertEquals(1, requester.asked)
        assertEquals(listOf("auth.signIn.fallback=addAccount"), logger.events)
        assertNull(store.get("account", "email"))
    }

    @Test
    fun aDismissedPickerDoesNotOpenAnother() = runTest {
        val requester = FakeRequester(Answer.Cancelled)
        val store = FakeSecureStore()

        assertEquals(SignInResult.Cancelled, auth(requester, store).signIn(activity))
        assertEquals(1, requester.asked)
        assertNull(store.get("account", "email"))
    }

    @Test
    fun aPlayServicesFailureIsAFailureWithItsReason() = runTest {
        val requester = FakeRequester(Answer.Broken("Play Services is out of date"))
        val store = FakeSecureStore()

        assertEquals(SignInResult.Failed("Play Services is out of date"), auth(requester, store).signIn(activity))
        assertEquals(1, requester.asked)
        assertNull(store.get("account", "email"))
    }

    private val logger = RecordingLogger()

    private val activity = Activity()

    private fun auth(requester: CredentialRequester, store: SecureStore = FakeSecureStore()) =
        GoogleAuth(
            // Activity is a Context; sign-in only ever uses it as the one to show a picker over.
            context = activity,
            secureStore = store,
            tokens = AndroidTokenProvider(UnusedAuthorizer, store, FixedClock),
            clock = FixedClock,
            logger = logger,
            serverClientId = "server-client-id",
            credentials = requester,
        )

    private sealed interface Answer {
        data class Email(val value: String) : Answer

        data object NoCredential : Answer

        data object Cancelled : Answer

        data class Broken(val message: String) : Answer
    }

    /** Gives one answer, and counts how often it was asked: a tap opens one picker, never two. */
    private class FakeRequester(private val answer: Answer) : CredentialRequester {
        var asked = 0

        override suspend fun requestEmail(activity: Activity): String {
            asked++
            return when (answer) {
                is Answer.Email -> answer.value
                Answer.NoCredential -> throw NoCredentialException("No credentials available")
                Answer.Cancelled -> throw GetCredentialCancellationException("cancelled")
                is Answer.Broken -> throw GetCredentialUnknownException(answer.message)
            }
        }
    }

    private class RecordingLogger : Logger {
        val events = mutableListOf<String>()

        override fun log(level: Logger.Level, event: String, fields: Map<String, Any?>, error: Throwable?) {
            events += event
        }
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(0)
    }

    /** Sign-in never authorizes; the token provider is only here because [GoogleAuth] holds one. */
    private object UnusedAuthorizer : Authorizer {
        override suspend fun authorize(): AuthorizeResult = error("sign-in must not authorize")

        override suspend fun clearToken(token: String) = error("sign-in must not clear tokens")
    }
}
