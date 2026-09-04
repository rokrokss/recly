@file:OptIn(ExperimentalTime::class)

package app.recly.android.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import recly.core.platform.AuthRequiredException
import recly.core.platform.Clock
import recly.core.platform.SecureStore

class AndroidTokenProviderTest {

    @Test
    fun fetchesAndCachesAToken() = runTest {
        val authorizer = FakeAuthorizer(granted("t1"))
        val store = FakeSecureStore()
        val provider = AndroidTokenProvider(authorizer, store, clock)

        assertEquals("t1", provider.accessToken())
        assertEquals("t1", provider.accessToken())
        assertEquals(1, authorizer.calls, "a live token must not go back to Play Services")
    }

    @Test
    fun reAuthorizesInsideTheRefreshMargin() = runTest {
        val authorizer = FakeAuthorizer(granted("t1"), granted("t2"))
        val provider = AndroidTokenProvider(authorizer, FakeSecureStore(), clock)

        assertEquals("t1", provider.accessToken())
        // 59 s of life left: docs/06 refreshes at 60 s.
        clock.instant = START + 1.hours - 59.seconds
        assertEquals("t2", provider.accessToken())
        assertEquals(2, authorizer.calls)
    }

    @Test
    fun keepsATokenThatIsStillOutsideTheMargin() = runTest {
        val authorizer = FakeAuthorizer(granted("t1"), granted("t2"))
        val provider = AndroidTokenProvider(authorizer, FakeSecureStore(), clock)

        assertEquals("t1", provider.accessToken())
        clock.instant = START + 1.hours - 61.seconds
        assertEquals("t1", provider.accessToken())
        assertEquals(1, authorizer.calls)
    }

    @Test
    fun readsTheStoredTokenBackOnAColdStart() = runTest {
        val store = FakeSecureStore()
        AndroidTokenProvider(FakeAuthorizer(granted("t1")), store, clock).accessToken()

        // A Worker in a fresh process: same secure store, an authorizer that would fail if used.
        val cold = FakeAuthorizer()
        assertEquals("t1", AndroidTokenProvider(cold, store, clock).accessToken())
        assertEquals(0, cold.calls)
    }

    @Test
    fun needsConsentBecomesAuthRequired() = runTest {
        val provider = AndroidTokenProvider(
            FakeAuthorizer(AuthorizeResult.NeedsConsent),
            FakeSecureStore(),
            clock,
        )
        assertFailsWith<AuthRequiredException> { provider.accessToken() }
    }

    @Test
    fun aTransientFailureIsNotAuthRequired() = runTest {
        val provider = AndroidTokenProvider(
            FakeAuthorizer(AuthorizeResult.Failed("offline")),
            FakeSecureStore(),
            clock,
        )
        val failure = assertFailsWith<AuthorizationFailedException> { provider.accessToken() }
        assertEquals("offline", failure.message)
    }

    @Test
    fun invalidateDropsBothTheCacheAndTheStore() = runTest {
        val authorizer = FakeAuthorizer(granted("t1"), granted("t2"))
        val store = FakeSecureStore()
        val provider = AndroidTokenProvider(authorizer, store, clock)

        assertEquals("t1", provider.accessToken())
        provider.invalidate()

        assertNull(store.get(SecureStore.TOKENS, "google_access_token"))
        assertNull(store.get(SecureStore.TOKENS, "google_access_token_expires_at"))
        assertEquals("t2", provider.accessToken(), "the rejected token must not come back")
        assertEquals(2, authorizer.calls)
    }

    @Test
    fun adoptedGrantsSatisfyTheCore() = runTest {
        val authorizer = FakeAuthorizer()
        val provider = AndroidTokenProvider(authorizer, FakeSecureStore(), clock)

        provider.adopt("interactive", START + 30.minutes)

        assertEquals("interactive", provider.accessToken())
        assertEquals(0, authorizer.calls, "an interactive grant is already a valid token")
    }

    @Test
    fun invalidateEvictsTheTokenFromPlayServices() = runTest {
        val authorizer = CachingAuthorizer(clock, "t1", "t2")
        val provider = AndroidTokenProvider(authorizer, FakeSecureStore(), clock)

        assertEquals("t1", provider.accessToken())
        provider.invalidate()

        assertEquals(listOf("t1"), authorizer.cleared, "the rejected token must be cleared exactly once")
        // Without the eviction the cached grant would come straight back as "t1".
        assertEquals("t2", provider.accessToken())
    }

    @Test
    fun theExpiryPathEvictsBeforeReauthorizing() = runTest {
        val authorizer = CachingAuthorizer(clock, "t1", "t2")
        val provider = AndroidTokenProvider(authorizer, FakeSecureStore(), clock)

        assertEquals("t1", provider.accessToken())
        clock.instant = START + 1.hours - 59.seconds

        // Without the eviction Play Services would hand "t1" back with a fresh hour on it and the
        // token would never actually roll over.
        assertEquals("t2", provider.accessToken())
        assertEquals(listOf("t1"), authorizer.cleared)
    }

    @Test
    fun aColdStartWithNothingHeldClearsNothing() = runTest {
        val authorizer = CachingAuthorizer(clock, "t1")
        val provider = AndroidTokenProvider(authorizer, FakeSecureStore(), clock)

        assertEquals("t1", provider.accessToken())
        assertEquals(emptyList(), authorizer.cleared)
    }

    private val clock = FakeClock()

    private fun granted(token: String) = AuthorizeResult.Granted(token, clock.now() + 1.hours)

    private class FakeAuthorizer(vararg results: AuthorizeResult) : Authorizer {
        private val queue = ArrayDeque(results.toList())
        var calls = 0
            private set
        val cleared = mutableListOf<String>()

        override suspend fun authorize(): AuthorizeResult {
            calls++
            return queue.removeFirstOrNull() ?: error("unexpected authorize() call")
        }

        override suspend fun clearToken(token: String) {
            cleared += token
        }
    }

    /**
     * Play Services as it actually behaves: `authorize()` keeps handing the same cached grant back
     * — with a fresh hour claimed on it — until that token is explicitly cleared.
     */
    private class CachingAuthorizer(private val clock: Clock, vararg tokens: String) : Authorizer {
        private val queue = ArrayDeque(tokens.toList())
        private var current: String? = null
        var calls = 0
            private set
        val cleared = mutableListOf<String>()

        override suspend fun authorize(): AuthorizeResult {
            calls++
            val token = current ?: queue.removeFirstOrNull()?.also { current = it }
                ?: error("ran out of tokens")
            return AuthorizeResult.Granted(token, clock.now() + 1.hours)
        }

        override suspend fun clearToken(token: String) {
            cleared += token
            if (current == token) current = null
        }
    }

    private class FakeClock(var instant: Instant = START) : Clock {
        override fun now(): Instant = instant
    }

    private companion object {
        val START: Instant = Instant.parse("2026-08-27T00:00:00Z")
    }
}
