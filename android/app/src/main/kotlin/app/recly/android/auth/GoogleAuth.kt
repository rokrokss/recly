@file:OptIn(ExperimentalTime::class)

package app.recly.android.auth

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlin.time.ExperimentalTime
import recly.core.message.CoreMessage
import recly.core.platform.Clock
import recly.core.platform.Logger
import recly.core.platform.SecureStore

/**
 * The interactive half of docs/06 Android, in two independent steps:
 *
 * 1. [signIn] — Credential Manager `GetGoogleIdOption`. This only identifies the account; the ID
 *    token is not a Drive credential and is not kept.
 * 2. [authorizeDrive] — `AuthorizationClient` for the two ADR-009 scopes. Silent for an account
 *    that already consented, otherwise a consent screen through [AuthResolver]. The grant is
 *    handed straight to [AndroidTokenProvider], which is what the core actually reads.
 */
class GoogleAuth(
    private val context: Context,
    private val secureStore: SecureStore,
    private val tokens: AndroidTokenProvider,
    private val clock: Clock,
    private val logger: Logger,
    private val serverClientId: String,
    private val credentials: CredentialRequester = CredentialManagerRequester(context, serverClientId),
) {
    /** The signed-in account's email, or null. Survives process death; cleared by [signOut]. */
    suspend fun account(): String? =
        secureStore.get(NS_ACCOUNT, KEY_EMAIL)?.decodeToString()

    /**
     * The [SignInMode] ladder of docs/06 Android: each rung is Google's documented answer to the
     * one before it finding no credential at all. Past the last rung there is no Google account on
     * the device, which only the system add-account screen can fix — [SignInResult.NoAccount], and
     * the UI opens that screen.
     *
     * `NoCredentialException` is the only exception worth descending on: a cancellation or a Play
     * Services failure means this rung *could* have worked, and retrying it lower would only put a
     * second picker in front of a user who just dismissed one.
     */
    suspend fun signIn(activity: Activity): SignInResult {
        SignInMode.entries.forEachIndexed { index, mode ->
            if (index > 0) logger.log(Logger.Level.INFO, "auth.signIn.fallback=${mode.label}")
            val email = try {
                credentials.requestEmail(activity, mode)
            } catch (e: NoCredentialException) {
                return@forEachIndexed
            } catch (e: Exception) {
                return SignInResult.Failed(e.message ?: e::class.java.simpleName)
            }
            secureStore.put(NS_ACCOUNT, KEY_EMAIL, email.encodeToByteArray())
            return SignInResult.SignedIn(email)
        }
        logger.log(Logger.Level.INFO, "auth.signIn.fallback=addAccount")
        return SignInResult.NoAccount
    }

    /**
     * Asks for `drive.file`, resolving the consent screen when Play Services
     * says one is needed. On success the token is already stored for the core.
     */
    suspend fun authorizeDrive(activity: Activity, resolver: AuthResolver): AuthorizeResult {
        val client = Identity.getAuthorizationClient(activity)
        val first = try {
            client.authorize(driveAuthorizationRequest()).await()
        } catch (e: Exception) {
            return AuthorizeResult.Failed(e.message ?: e::class.java.simpleName)
        }
        val resolved = if (!first.hasResolution()) {
            first
        } else {
            val pending = first.pendingIntent
                ?: return AuthorizeResult.Failed(CoreMessage.DRIVE_CONSENT_REQUIRED.code())
            // docs/07 §5: the reasons the user acts on are keys; the rest are Play Services' own
            // words and reach the screen as they are.
            val data = resolver.resolve(pending)
                ?: return AuthorizeResult.Failed(CoreMessage.SIGN_IN_CANCELLED.code())
            try {
                client.getAuthorizationResultFromIntent(data)
            } catch (e: Exception) {
                return AuthorizeResult.Failed(e.message ?: e::class.java.simpleName)
            }
        }
        return resolved.toAuthorizeResult(clock.now()).also {
            if (it is AuthorizeResult.Granted) tokens.adopt(it.accessToken, it.expiresAt)
        }
    }

    suspend fun signOut() {
        tokens.invalidate()
        secureStore.delete(NS_ACCOUNT, KEY_EMAIL)
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    /**
     * "연결 해제" (docs/03), the half that is not the core's: `AuthorizationClient.revokeAccess`
     * hands the two ADR-009 scopes back to Google for this account, which is what makes the other
     * devices lose access too. docs/06 is explicit that the ordinary sign-out never does this.
     *
     * The sign-out follows a revoke that worked, because an identity kept next to a grant that is
     * gone is the state that only looks signed in. A revoke that failed leaves everything as it
     * was and says so — the screen offers the Google account permissions page instead.
     */
    suspend fun revokeAccess(): RevokeResult {
        val email = account() ?: return RevokeResult.NotSignedIn
        try {
            Identity.getAuthorizationClient(context)
                .revokeAccess(
                    RevokeAccessRequest.builder()
                        .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
                        .setScopes(DRIVE_SCOPES)
                        .build(),
                )
                .await()
        } catch (e: Exception) {
            logger.log(Logger.Level.WARN, "auth.revoke.failed", error = e)
            return RevokeResult.Failed(e.message ?: e::class.java.simpleName)
        }
        signOut()
        return RevokeResult.Revoked
    }

    private companion object {
        const val NS_ACCOUNT = "account"
        const val KEY_EMAIL = "email"

        /** `AccountManager`'s type for a Google account — what `RevokeAccessRequest` asks for. */
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}

/** What [GoogleAuth.revokeAccess] managed — docs/03's "연결 해제", minus the core's own clean-up. */
sealed interface RevokeResult {
    data object Revoked : RevokeResult

    /** Nothing to revoke: the local half of the disconnect is still worth doing. */
    data object NotSignedIn : RevokeResult

    data class Failed(val reason: String) : RevokeResult
}

sealed interface SignInResult {
    data class SignedIn(val email: String) : SignInResult

    /** No Google account on the device at all: the user has to add one before signing in. */
    data object NoAccount : SignInResult

    data class Failed(val reason: String) : SignInResult
}

/**
 * Which Credential Manager option [GoogleAuth.signIn] is asking with, in the order Google's guide
 * puts them (developer.android.com/identity/sign-in/credential-manager-siwg-implementation).
 * [label] is what the fallback log line says on the way down.
 */
enum class SignInMode(internal val label: String) {
    /** Bottom sheet, previously authorized accounts only: a returning user gets no picker at all. */
    AUTHORIZED("authorized"),

    /** Bottom sheet, every Google account on the device — the documented retry for a first sign-in. */
    ALL_ACCOUNTS("allAccounts"),

    /** The "Sign in with Google" button flow, which is also the one that offers "add an account". */
    BUTTON("button"),
}

/**
 * The Credential Manager round trip, behind an interface so [GoogleAuth.signIn]'s fallback ladder
 * is testable: both the options and the credential parsing need Play Services.
 */
interface CredentialRequester {
    /**
     * The chosen account's email address. Throws `NoCredentialException` when Credential Manager
     * has nothing to offer for [mode].
     */
    suspend fun requestEmail(activity: Activity, mode: SignInMode): String
}

internal class CredentialManagerRequester(
    private val context: Context,
    private val serverClientId: String,
) : CredentialRequester {

    override suspend fun requestEmail(activity: Activity, mode: SignInMode): String {
        // No nonce anywhere: the ID token is never sent to a server here (docs/06 — it identifies
        // the account and is thrown away), so there is no replay for a nonce to bind against.
        val option = when (mode) {
            SignInMode.AUTHORIZED -> GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setFilterByAuthorizedAccounts(true)
                // Exactly one already-authorized account: sign the returning user straight in.
                .setAutoSelectEnabled(true)
                .build()

            SignInMode.ALL_ACCOUNTS -> GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setFilterByAuthorizedAccounts(false)
                .build()

            SignInMode.BUTTON -> GetSignInWithGoogleOption.Builder(serverClientId).build()
        }
        val response = CredentialManager.create(context)
            .getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val credential = response.credential
        check(
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        ) { "unexpected credential ${credential.type}" }
        // GoogleIdTokenCredential.id is the account's email address.
        return GoogleIdTokenCredential.createFrom(credential.data).id
    }
}

/**
 * Runs a consent `PendingIntent` and returns its result Intent, or null if the user backed out.
 * The activity owns this because only it has an `ActivityResultLauncher`.
 */
interface AuthResolver {
    suspend fun resolve(pendingIntent: PendingIntent): Intent?
}
