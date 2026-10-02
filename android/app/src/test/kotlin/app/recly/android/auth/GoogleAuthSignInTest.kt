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
 * The docs/06 explicit connection flow. Play Services is behind [CredentialRequester], so a
 * stalled system account sheet can be reproduced without a real Google account.
 */
class GoogleAuthSignInTest {

    @Test
    fun aConnectTapSignsInWithoutWaitingForTheSystemAccountSheet() = runTest {
        val requester = object : CredentialRequester {
            override suspend fun requestEmail(activity: Activity, mode: SignInMode): String =
                if (mode == SignInMode.BUTTON) "a@example.com" else kotlinx.coroutines.awaitCancellation()
        }
        val store = FakeSecureStore()

        val result = kotlinx.coroutines.withTimeoutOrNull(1_000) {
            auth(requester, store).signIn(activity)
        }

        assertEquals(SignInResult.SignedIn("a@example.com"), result)
        assertEquals("a@example.com", store.get("account", "email")?.decodeToString())
    }

    @Test
    fun aDeviceWithNoGoogleAccountAsksForOne() = runTest {
        val requester = FakeRequester(SignInMode.BUTTON to Answer.NoCredential)
        val store = FakeSecureStore()

        assertEquals(SignInResult.NoAccount, auth(requester, store).signIn(activity))
        assertEquals(listOf(SignInMode.BUTTON), requester.asked)
        assertEquals(listOf("auth.signIn.fallback=addAccount"), logger.events)
        assertNull(store.get("account", "email"))
    }

    @Test
    fun aDismissedPickerDoesNotOpenAnother() = runTest {
        val requester = FakeRequester(SignInMode.BUTTON to Answer.Cancelled)
        val store = FakeSecureStore()

        assertEquals(SignInResult.Cancelled, auth(requester, store).signIn(activity))
        assertEquals(listOf(SignInMode.BUTTON), requester.asked)
        assertNull(store.get("account", "email"))
    }

    @Test
    fun aPlayServicesFailureIsAFailureWithItsReason() = runTest {
        val requester = FakeRequester(SignInMode.BUTTON to Answer.Broken("Play Services is out of date"))
        val store = FakeSecureStore()

        assertEquals(SignInResult.Failed("Play Services is out of date"), auth(requester, store).signIn(activity))
        assertEquals(listOf(SignInMode.BUTTON), requester.asked)
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

    /** Answers only the requested flow; an unexpected system account sheet must fail the test. */
    private class FakeRequester(vararg answers: Pair<SignInMode, Answer>) : CredentialRequester {
        private val answers = answers.toMap()
        val asked = mutableListOf<SignInMode>()

        override suspend fun requestEmail(activity: Activity, mode: SignInMode): String {
            asked += mode
            return when (val answer = answers[mode] ?: error("unexpected request for $mode")) {
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
