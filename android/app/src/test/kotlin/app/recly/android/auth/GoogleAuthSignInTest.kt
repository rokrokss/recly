@file:OptIn(ExperimentalTime::class)

package app.recly.android.auth

import android.app.Activity
import androidx.credentials.exceptions.GetCredentialCancellationException
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
 * The docs/06 Credential Manager ladder. The Play Services round trip is behind
 * [CredentialRequester], so what is under test here is exactly the part that decides how far down
 * the ladder to go and what the UI is told.
 */
class GoogleAuthSignInTest {

    @Test
    fun theFirstAuthorizedAccountSignsInWithoutFallingBack() = runTest {
        val requester = FakeRequester(SignInMode.AUTHORIZED to Answer.Email("a@example.com"))
        val store = FakeSecureStore()

        assertEquals(SignInResult.SignedIn("a@example.com"), auth(requester, store).signIn(activity))

        assertEquals(listOf(SignInMode.AUTHORIZED), requester.asked)
        assertEquals("a@example.com", store.get("account", "email")?.decodeToString())
        assertEquals(emptyList(), logger.events, "nothing fell back")
    }

    @Test
    fun aFirstSignInFallsThroughToTheAccountsSheet() = runTest {
        val requester = FakeRequester(
            SignInMode.AUTHORIZED to Answer.NoCredential,
            SignInMode.ALL_ACCOUNTS to Answer.Email("b@example.com"),
        )

        assertEquals(SignInResult.SignedIn("b@example.com"), auth(requester).signIn(activity))

        assertEquals(listOf(SignInMode.AUTHORIZED, SignInMode.ALL_ACCOUNTS), requester.asked)
        assertEquals(listOf("auth.signIn.fallback=allAccounts"), logger.events)
    }

    @Test
    fun noAccountSheetFallsThroughToTheSignInWithGoogleButton() = runTest {
        val requester = FakeRequester(
            SignInMode.AUTHORIZED to Answer.NoCredential,
            SignInMode.ALL_ACCOUNTS to Answer.NoCredential,
            SignInMode.BUTTON to Answer.Email("c@example.com"),
        )

        assertEquals(SignInResult.SignedIn("c@example.com"), auth(requester).signIn(activity))

        assertEquals(SignInMode.entries.toList(), requester.asked)
        assertEquals(
            listOf("auth.signIn.fallback=allAccounts", "auth.signIn.fallback=button"),
            logger.events,
        )
    }

    @Test
    fun aDeviceWithNoGoogleAccountAsksForOne() = runTest {
        val requester = FakeRequester(
            SignInMode.AUTHORIZED to Answer.NoCredential,
            SignInMode.ALL_ACCOUNTS to Answer.NoCredential,
            SignInMode.BUTTON to Answer.NoCredential,
        )
        val store = FakeSecureStore()

        // The whole point: "No credentials available" is not the end of the road.
        assertEquals(SignInResult.NoAccount, auth(requester, store).signIn(activity))

        assertEquals(SignInMode.entries.toList(), requester.asked, "every rung is tried first")
        assertEquals(
            listOf(
                "auth.signIn.fallback=allAccounts",
                "auth.signIn.fallback=button",
                "auth.signIn.fallback=addAccount",
            ),
            logger.events,
        )
        assertNull(store.get("account", "email"), "nothing is signed in")
    }

    @Test
    fun aDismissedPickerDoesNotOpenAnother() = runTest {
        val requester = FakeRequester(
            SignInMode.AUTHORIZED to Answer.NoCredential,
            SignInMode.ALL_ACCOUNTS to Answer.Cancelled,
        )

        assertEquals(
            SignInResult.Failed("cancelled"),
            auth(requester).signIn(activity),
            "a cancellation is the user's answer, not a reason to descend",
        )
        assertEquals(listOf(SignInMode.AUTHORIZED, SignInMode.ALL_ACCOUNTS), requester.asked)
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
    }

    /** Answers each rung of the ladder once; being asked anything else is the test failing. */
    private class FakeRequester(vararg answers: Pair<SignInMode, Answer>) : CredentialRequester {
        private val answers = answers.toMap()
        val asked = mutableListOf<SignInMode>()

        override suspend fun requestEmail(activity: Activity, mode: SignInMode): String {
            asked += mode
            return when (val answer = answers[mode] ?: error("unexpected request for $mode")) {
                is Answer.Email -> answer.value
                Answer.NoCredential -> throw NoCredentialException("No credentials available")
                Answer.Cancelled -> throw GetCredentialCancellationException("cancelled")
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
