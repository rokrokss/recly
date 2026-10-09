@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package recly.core.chatgpt

import io.ktor.http.Url
import io.ktor.http.formUrlEncode
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import recly.core.db.RecDatabase
import recly.core.message.CoreMessage
import recly.core.platform.CoreDeps
import recly.core.platform.HttpBody
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult
import recly.core.platform.Logger
import recly.core.transcribe.OpenAiAvailability
import recly.core.transcribe.providerJson

/** One model the signed-in plan offers, as `GET /v1/models` lists it. [label] is OpenAI's own name for it. */
data class ChatGptModel(val id: String, val label: String)

/** What Settings → ChatGPT shows (docs/09 "Summary view"). */
sealed class ChatGptConnection {
    /** docs/15 "China mainland App Store": not offered in this storefront — the shells show nothing. */
    data object Unavailable : ChatGptConnection()

    data object SignedOut : ChatGptConnection()

    /**
     * [models] is empty when the list could not be read (offline); [model] is the one a summary uses —
     * the user's pick while the plan still lists it, otherwise the first one OpenAI lists.
     */
    data class SignedIn(val account: String, val models: List<ChatGptModel>, val model: String?) : ChatGptConnection()

    /** OpenAI ended the sign-in — expired, revoked, or disconnected in ChatGPT settings. Signing in again restores it. */
    data class Expired(val account: String) : ChatGptConnection()
}

/**
 * A sign-in the shell has started: open [authorizationUrl] in the browser, and hand the callback whose `state`
 * query value is [state] to [ChatGptAccount.finishSignIn] — after answering the browser at once, so its page
 * does not wait on the token exchange.
 */
data class ChatGptSignIn(val authorizationUrl: String, val state: String)

/** A call that either worked or says why, as a [CoreMessage] wire code the shell renders (docs/07 §5). */
sealed class ChatGptResult {
    /** [welcome]: the first sign-in on this device, which the shells confirm once (docs/09 "Summary view"). */
    data class Done(val welcome: Boolean = false) : ChatGptResult()

    data class Failed(val reason: String) : ChatGptResult()
}

/** A failure with its [CoreMessage] wire code; caught inside the core and turned into a result. */
internal class ChatGptFailure(val reason: String) : Exception(reason)

/**
 * docs/15 §10 "Sign in with ChatGPT": the user's own ChatGPT plan, through OpenAI's open-source flow
 * (<https://developers.openai.com/siwc/token-sharing-open-source/sign-in>, checked 2026-10-09). The first
 * sign-in registers this installation as a public client (`dynamic_agent_client`, no secret); every later
 * one reuses the client id OpenAI issued, which is kept through sign-outs as OpenAI asks. The redirect is a
 * loopback the shell listens on — OpenAI accepts only `http://127.0.0.1:<port>/auth/callback` — so the shell
 * owns the socket and the browser, and this class owns the rest: PKCE, the exchange, the rotating refresh
 * token, the model list.
 *
 * Kept in its own [recly.core.platform.SecureStore] namespace: a Drive disconnect empties `tokens`
 * (docs/05 "Tokens"), and has nothing to do with this account.
 */
class ChatGptAccount internal constructor(
    private val db: RecDatabase,
    private val deps: CoreDeps,
) {
    private val state = MutableStateFlow<ChatGptConnection>(ChatGptConnection.SignedOut)

    /** Every credential read-modify-write and every refresh, one at a time: the refresh token rotates. */
    private val mutex = Mutex()

    private var pending: Pending? = null

    fun observe(): StateFlow<ChatGptConnection> = state.asStateFlow()

    /**
     * Reads what this device holds and, when signed in, the plan's models. A secure store that will not
     * be read throws rather than reading as signed out (docs/05 "fails closed").
     */
    @Throws(Throwable::class)
    suspend fun refresh(): ChatGptConnection {
        if (!available()) return publish(ChatGptConnection.Unavailable)
        val (registration, session) = mutex.withLock { registration() to session() }
        if (registration == null || session == null) return publish(ChatGptConnection.SignedOut)
        val models = try {
            listModels()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline keeps the sign-in; a refused refresh has already said Expired.
            if (state.value is ChatGptConnection.Expired) return state.value
            emptyList()
        }
        return publish(ChatGptConnection.SignedIn(registration.account, models, pick(models)))
    }

    /**
     * Starts a sign-in on [redirectUri], the loopback the shell is listening on. A second call replaces the
     * first: only the newest sign-in can finish.
     */
    @Throws(Throwable::class)
    suspend fun beginSignIn(redirectUri: String): ChatGptSignIn {
        check(available()) { "ChatGPT is not offered here" }
        require(LOOPBACK.matches(redirectUri)) { "Not a loopback callback: $redirectUri" }
        val (registration, host) = mutex.withLock { registration() to hostId() }
        val next = Pending(random(), random(), random() + random(), redirectUri, registration?.clientId)
        pending = next
        val parameters = buildList {
            add("client_id" to (registration?.clientId ?: REGISTRATION))
            add("response_type" to "code")
            add("resource" to API)
            add("scope" to SCOPE)
            add("redirect_uri" to redirectUri)
            add("state" to next.state)
            add("nonce" to next.nonce)
            add("code_challenge" to next.verifier.encodeUtf8().sha256().base64Url().trimEnd('='))
            add("code_challenge_method" to "S256")
            add("ext_agent_host_id" to host)
            // The app's name goes only with a new registration; a returning one is recognised by its client id.
            if (registration == null) add("agent_name_hint" to AGENT_NAME)
            registration?.account?.takeIf { '@' in it }?.let { add("login_hint" to it) }
        }
        return ChatGptSignIn("$AUTH/api/accounts/authorize?" + parameters.formUrlEncode(), next.state)
    }

    /** The shell gave up on the sign-in it started (the user closed the browser sheet, a timeout). */
    fun cancelSignIn() {
        pending = null
    }

    /**
     * [callbackUrl] is the whole URL the browser brought to the loopback. A callback whose `state` is not
     * the newest sign-in's is refused without touching what is saved.
     */
    @Throws(Throwable::class)
    suspend fun finishSignIn(callbackUrl: String): ChatGptResult = result {
        val started = pending ?: throw ChatGptFailure(CoreMessage.SIGN_IN_CANCELLED.code())
        val query = Url(callbackUrl).parameters
        if (query["state"] != started.state) throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "state mismatch"))
        pending = null
        query["error"]?.let { error ->
            throw ChatGptFailure(
                if (error == "access_denied") CoreMessage.SIGN_IN_CANCELLED.code()
                else CoreMessage.PROVIDER_ERROR.code(detail = error),
            )
        }
        // A new registration learns its client id here; a returning one may get none back, or must get its own.
        val returned = query["client_id"]?.takeIf { it.isNotEmpty() }
        val clientId = started.clientId ?: returned?.takeIf { it != REGISTRATION }
            ?: throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "no client id"))
        if (returned != null && returned != clientId) throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "client id changed"))
        val code = query["code"] ?: throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "no code"))
        val tokens = tokenRequest(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "code_verifier" to started.verifier,
            "redirect_uri" to started.redirectUri,
        )
        val identity = identity(tokens, clientId, started.nonce)
        val session = session(tokens, previous = null)
        mutex.withLock {
            val saved = registration()
            if (saved != null && saved.subject != identity.subject) {
                // A client id is one account's (docs/15 §10). Another account signed in on it: keep nothing,
                // and let the next sign-in register that account afresh.
                delete(REGISTRATION_KEY)
                revokeQuietly(clientId, session.refresh)
                throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "account changed"))
            }
            val account = identity.email ?: identity.name ?: saved?.account ?: AGENT_NAME
            write(REGISTRATION_KEY, providerJson.encodeToString(Registration.serializer(), Registration(clientId, identity.subject, account)))
            writeSession(session)
        }
        deps.logger.log(Logger.Level.INFO, "chatgpt.signin", mapOf("registered" to (started.clientId == null)))
        refresh()
        ChatGptResult.Done(welcome = started.clientId == null)
    }

    /**
     * Revokes the sign-in at OpenAI, then forgets its tokens here. The registration stays, so signing in again
     * reuses the client id instead of adding another Recly to the account. [ChatGptResult.Failed] means OpenAI
     * could not be told — signed out on this device all the same; ChatGPT settings can disconnect Recly.
     */
    @Throws(Throwable::class)
    suspend fun signOut(): ChatGptResult {
        val (clientId, refresh) = mutex.withLock {
            val held = registration()?.clientId to session()?.refresh
            deleteSession()
            held
        }
        publish(if (available()) ChatGptConnection.SignedOut else ChatGptConnection.Unavailable)
        deps.logger.log(Logger.Level.INFO, "chatgpt.signout", emptyMap())
        if (clientId == null || refresh == null) return ChatGptResult.Done()
        return result {
            revoke(clientId, refresh)
            ChatGptResult.Done()
        }
    }

    /** The model a summary uses from now on. Not a secret; kept with the device's other preferences. */
    @Throws(Throwable::class)
    suspend fun selectModel(id: String) {
        withContext(deps.io) { db.recQueries.kvSet(MODEL_KEY, id) }
        val current = state.value
        if (current is ChatGptConnection.SignedIn) publish(current.copy(model = pick(current.models)))
    }

    /**
     * Runs [call] with an access token; on a 401 it refreshes once and runs it again. A 401 to a token
     * just issued confirms OpenAI no longer accepts this sign-in (disconnected in ChatGPT settings): it
     * ends here too, as docs "Disconnection" asks.
     */
    internal suspend fun authorized(call: suspend (token: String) -> HttpResult): HttpResult {
        if (!available()) throw ChatGptFailure(CoreMessage.PROVIDER_REGION_RESTRICTED.code())
        val first = call(accessToken(force = false))
        if (first.status != 401) return first
        val second = call(accessToken(force = true))
        if (second.status == 401) {
            mutex.withLock {
                val account = registration()?.account
                deleteSession()
                if (account != null) publish(ChatGptConnection.Expired(account))
            }
            deps.logger.log(Logger.Level.WARN, "chatgpt.rejected", emptyMap())
        }
        return second
    }

    /** The model a summary should use now, or null when there is none to pick. */
    internal suspend fun model(): String? {
        val current = state.value
        if (current is ChatGptConnection.SignedIn && current.model != null) return current.model
        return pick(listModels())
    }

    private suspend fun available(): Boolean {
        val policy = deps.transcriptionPolicy
        return !policy.enabled || policy.refresh() == OpenAiAvailability.ALLOWED
    }

    /**
     * The access token, refreshed when it is within a minute of expiring — but not before the
     * `earliest_refresh_at` OpenAI sent with it, as long as it still works. [force] (a 401) refreshes now.
     */
    private suspend fun accessToken(force: Boolean): String = mutex.withLock {
        val registration = registration() ?: throw ChatGptFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code())
        val current = session() ?: throw ChatGptFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code())
        val now = deps.clock.now().epochSeconds
        if (!force && current.expiresAt > now + 60) return@withLock current.access
        if (!force && (current.earliestRefreshAt ?: 0) > now) {
            if (current.expiresAt > now) return@withLock current.access
            throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "refresh not ready"))
        }
        val tokens = try {
            tokenRequest(
                "grant_type" to "refresh_token",
                "client_id" to registration.clientId,
                "refresh_token" to current.refresh,
            )
        } catch (e: ChatGptFailure) {
            if (e.reason == CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code()) {
                // Unusable for good: drop the tokens, keep the registration for the next sign-in.
                deleteSession()
                publish(ChatGptConnection.Expired(registration.account))
                deps.logger.log(Logger.Level.WARN, "chatgpt.refresh.refused", emptyMap())
            }
            throw e
        }
        // The replacement refresh token is the only one that works now: it is saved even if the caller has gone.
        withContext(NonCancellable) {
            val next = session(tokens, previous = current)
            writeSession(next)
            if (tokens.string("id_token") != null) identity(tokens, registration.clientId, nonce = null, subject = registration.subject)
            else requirePlanScope(tokens)
            next.access
        }
    }

    private suspend fun listModels(): List<ChatGptModel> {
        val result = authorized { token ->
            deps.transport.execute(
                HttpPlan("GET", "$API/models", headers = mapOf("Authorization" to "Bearer $token"), followRedirects = false, timeoutSec = 25),
            )
        }
        if (result.status !in 200..299) throw failure(result)
        val models = json(result)["models"] as? JsonArray
            ?: throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "no model list"))
        // `visibility: list` is what the plan offers people to pick, in OpenAI's own order.
        return models.mapNotNull { item ->
            val model = item as? JsonObject ?: return@mapNotNull null
            val id = model.string("slug") ?: return@mapNotNull null
            if (model.string("visibility") != "list") return@mapNotNull null
            ChatGptModel(id, model.string("display_name") ?: id)
        }
    }

    private suspend fun pick(models: List<ChatGptModel>): String? {
        val chosen = withContext(deps.io) { db.recQueries.kvGet(MODEL_KEY).executeAsOneOrNull() }
        if (models.isEmpty()) return chosen
        return models.firstOrNull { it.id == chosen }?.id ?: models.first().id
    }

    private suspend fun tokenRequest(vararg parameters: Pair<String, String>): JsonObject {
        val body = (parameters.toList() + ("resource" to API)).formUrlEncode()
        val result = send(
            HttpPlan(
                "POST", "$AUTH/api/accounts/oauth/token",
                body = HttpBody.Text(body, "application/x-www-form-urlencoded"),
                followRedirects = false,
                timeoutSec = 30,
            ),
        )
        if (result.status in 200..299) {
            return json(result).also {
                it.string("access_token") ?: throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "no access token"))
            }
        }
        val body2 = runCatching { json(result) }.getOrNull()
        val error = body2?.string("error") ?: (body2?.get("error") as? JsonObject)?.string("code")
        // docs "Refresh errors": these mean the token is gone for good; only signing in again helps.
        if (error in UNUSABLE_GRANT) throw ChatGptFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code())
        throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "token ${result.status} ${error.orEmpty()}".trim()))
    }

    /**
     * The ID token's claims. Its signature is not checked: it came straight from OpenAI's token endpoint
     * over TLS in answer to this request, which OpenID Connect Core 1.0 §3.1.3.7 (6) accepts in place of
     * the signature. What is checked is that it is OpenAI's, for this client, unexpired, from this sign-in
     * ([nonce]) and, on a refresh, still the same person — and that the grant includes the plan.
     */
    private fun identity(tokens: JsonObject, clientId: String, nonce: String?, subject: String? = null): Identity {
        requirePlanScope(tokens)
        val token = tokens.string("id_token") ?: throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "no id token"))
        val claims = token.split('.').getOrNull(1)?.decodeBase64()?.utf8()
            ?.let { runCatching { providerJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "unreadable id token"))
        val audience = claims["aud"]
        val audiences = (audience as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: listOfNotNull((audience as? JsonPrimitive)?.contentOrNull)
        val expires = (claims["exp"] as? JsonPrimitive)?.longOrNull
        val problem = when {
            claims.string("iss") != AUTH -> "issuer"
            clientId !in audiences -> "audience"
            expires == null || expires < deps.clock.now().epochSeconds -> "expired"
            nonce != null && claims.string("nonce") != nonce -> "nonce"
            claims.string("sub").isNullOrEmpty() -> "subject"
            subject != null && claims.string("sub") != subject -> "account changed"
            else -> null
        }
        if (problem != null) throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "id token $problem"))
        return Identity(claims.string("sub")!!, claims.string("email"), claims.string("name"))
    }

    /** Without this scope the token signs in but cannot use the plan (docs "ChatGPT plan use isn't enabled"). */
    private fun requirePlanScope(tokens: JsonObject) {
        if (DIRECT_SCOPE !in tokens.string("scope")?.split(' ').orEmpty()) {
            throw ChatGptFailure(CoreMessage.CHATGPT_PLAN_REQUIRED.code())
        }
    }

    /** A token response as what is kept; a refresh may leave out the refresh or ID token it did not replace. */
    private fun session(tokens: JsonObject, previous: Session?): Session {
        val now = deps.clock.now().epochSeconds
        val lifetime = (tokens["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
            ?: throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "no expiry"))
        return Session(
            refresh = tokens.string("refresh_token") ?: previous?.refresh
                ?: throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "no refresh token")),
            access = tokens.string("access_token")!!,
            expiresAt = now + lifetime,
            earliestRefreshAt = epochSeconds(tokens["earliest_refresh_at"]),
            idToken = tokens.string("id_token") ?: previous?.idToken,
        )
    }

    /** OpenAI sends `earliest_refresh_at` as Unix seconds; a string of digits is read the same way. */
    private fun epochSeconds(value: Any?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
            ?: primitive.contentOrNull?.let { runCatching { kotlin.time.Instant.parse(it).epochSeconds }.getOrNull() }
    }

    /** docs "End the renewable session": retried on a network failure or 5xx, a few times. */
    private suspend fun revoke(clientId: String, refresh: String) {
        val discovery = json(send(HttpPlan("GET", "$AUTH/.well-known/openid-configuration", followRedirects = false, timeoutSec = 20)))
        val endpoint = discovery.string("revocation_endpoint")
            ?.takeIf { it.startsWith("$AUTH/") }
            ?: throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "no revocation endpoint"))
        val body = listOf("token" to refresh, "token_type_hint" to "refresh_token", "client_id" to clientId).formUrlEncode()
        var last = "revoke"
        repeat(3) { attempt ->
            val result = try {
                send(HttpPlan("POST", endpoint, body = HttpBody.Text(body, "application/x-www-form-urlencoded"), followRedirects = false, timeoutSec = 20))
            } catch (e: ChatGptFailure) {
                null
            }
            if (result != null && result.status in 200..299) return
            if (result != null && result.status < 500) throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "revoke ${result.status}"))
            last = "revoke ${result?.status ?: "offline"}"
            delay(250L shl attempt)
        }
        throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = last))
    }

    private suspend fun revokeQuietly(clientId: String, refresh: String) {
        try {
            revoke(clientId, refresh)
        } catch (e: ChatGptFailure) {
            deps.logger.log(Logger.Level.WARN, "chatgpt.revoke.failed", emptyMap())
        }
    }

    private suspend fun send(plan: HttpPlan): HttpResult = try {
        deps.transport.execute(plan)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = e.message ?: e::class.simpleName))
    }

    private suspend fun registration(): Registration? = readString(REGISTRATION_KEY)?.let {
        runCatching { providerJson.decodeFromString(Registration.serializer(), it) }.getOrNull()
    }

    private suspend fun session(): Session? = readPieces(SESSION_KEY)?.let {
        runCatching { providerJson.decodeFromString(Session.serializer(), it) }.getOrNull()
    }

    private suspend fun writeSession(session: Session) =
        writePieces(SESSION_KEY, providerJson.encodeToString(Session.serializer(), session))

    private suspend fun deleteSession() = writePieces(SESSION_KEY, "")

    /** docs/15 §10: `ext_agent_host_id` — one per installation, made once and kept through sign-outs. */
    private suspend fun hostId(): String =
        readString(HOST_KEY) ?: "urn:uuid:${Uuid.random()}".also { write(HOST_KEY, it) }

    /**
     * A Windows credential holds 2,560 bytes (docs/05 "Secrets") and the session's tokens are longer, so
     * a long value is kept as `name.0`, `name.1`, … — ASCII, [PIECE] characters each.
     */
    private suspend fun writePieces(name: String, value: String) {
        val pieces = value.chunked(PIECE)
        pieces.forEachIndexed { index, piece -> write("$name.$index", piece) }
        var index = pieces.size
        while (readString("$name.$index") != null) delete("$name.${index++}")
    }

    private suspend fun readPieces(name: String): String? = buildString {
        var index = 0
        while (true) append(readString("$name.${index++}") ?: break)
    }.ifEmpty { null }

    private suspend fun readString(key: String): String? = deps.secureStore.get(NAMESPACE, key)?.decodeToString()

    private suspend fun write(key: String, value: String) = deps.secureStore.put(NAMESPACE, key, value.encodeToByteArray())

    private suspend fun delete(key: String) = deps.secureStore.delete(NAMESPACE, key)

    private fun publish(connection: ChatGptConnection): ChatGptConnection = connection.also { state.value = it }

    private suspend fun result(block: suspend () -> ChatGptResult): ChatGptResult = try {
        block()
    } catch (e: ChatGptFailure) {
        deps.logger.log(Logger.Level.WARN, "chatgpt.failed", mapOf("reason" to e.reason.substringBefore('|')))
        ChatGptResult.Failed(e.reason)
    }

    @Serializable
    private data class Registration(val clientId: String, val subject: String, val account: String)

    /** Times are Unix seconds. */
    @Serializable
    private data class Session(
        val refresh: String,
        val access: String,
        val expiresAt: Long,
        val earliestRefreshAt: Long? = null,
        val idToken: String? = null,
    )

    private data class Pending(
        val state: String,
        val nonce: String,
        val verifier: String,
        val redirectUri: String,
        val clientId: String?,
    )

    private data class Identity(val subject: String, val email: String?, val name: String?)

    internal companion object {
        const val AUTH = "https://auth.openai.com"
        const val API = "https://api.openai.com/v1"
        const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        const val DIRECT_SCOPE = "chatgpt.tokens.use.direct"
        const val REGISTRATION = "dynamic_agent_client"
        const val AGENT_NAME = "Recly"

        const val NAMESPACE = "chatgpt"
        const val HOST_KEY = "host"
        const val REGISTRATION_KEY = "registration"
        const val SESSION_KEY = "session"
        const val PIECE = 2000
        const val MODEL_KEY = "chatgpt/model"

        /** docs "Refresh errors" (checked 2026-10-09). */
        val UNUSABLE_GRANT = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused",
        )

        /** Only the port may vary (docs/15 §10); OpenAI refuses `localhost`. */
        val LOOPBACK = Regex("^http://127\\.0\\.0\\.1:\\d{1,5}/auth/callback$")

        /** 256 bits from the platform's secure generator, base64url — a state, a nonce, half a verifier. */
        fun random(): String =
            (Uuid.random().toByteArray() + Uuid.random().toByteArray()).toByteString().base64Url().trimEnd('=')

        fun json(result: HttpResult): JsonObject = runCatching {
            providerJson.parseToJsonElement(result.body.decodeToString()).jsonObject
        }.getOrElse { throw ChatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "unreadable response ${result.status}")) }

        fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

        /**
         * A failed call to the plan's API, by docs "Errors and recovery" (checked 2026-10-09): the codes with a
         * recovery the user can act on get their own sentence, the rest are a provider error with the code
         * or the admission `detail` shown under it.
         */
        fun failure(result: HttpResult): ChatGptFailure {
            val body = runCatching { json(result) }.getOrNull()
            val code = (body?.get("error") as? JsonObject)?.string("code")
            return ChatGptFailure(
                when (code) {
                    "subscription_sharing_usage_limit_exceeded" -> CoreMessage.CHATGPT_USAGE_LIMIT.code()
                    "subscription_sharing_user_not_eligible" -> CoreMessage.CHATGPT_PLAN_REQUIRED.code()
                    "subscription_sharing_invalid_user" -> CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code()
                    null -> when (result.status) {
                        401 -> CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code()
                        429 -> CoreMessage.CHATGPT_USAGE_LIMIT.code()
                        else -> CoreMessage.PROVIDER_ERROR.code(detail = "${result.status} ${body?.string("detail").orEmpty()}".trim())
                    }
                    else -> CoreMessage.PROVIDER_ERROR.code(detail = "${result.status} $code")
                },
            )
        }
    }
}
