package app.recly.windows.core

import app.recly.windows.i18n.Str
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import recly.core.platform.SecureStore
import recly.core.secrets.SecretsRepository
import recly.core.webhook.Signer

/**
 * The `secrets` namespace as the editor window sees it (docs/05 "시크릿": names travel in
 * `workflows.json`, values never leave this device).
 *
 * An interface with one shipped implementation, because [SecretStore] now needs the core's own
 * repository behind it and the editor's tests have no open database to give it.
 */
interface Secrets {
    suspend fun names(): List<String>

    suspend fun put(name: String, value: String)

    suspend fun delete(name: String)

    /**
     * docs/04's `whsec_…`. `SecureRandom`, not `Random.Default`: this is a signing key, and Kotlin's
     * default generator is not a cryptographic one.
     */
    fun generate(): String = Signer.generateSecret(SecureRandom().asKotlinRandom())
}

/**
 * The same three operations the phone's `SecretStore` has, and for the same reason through the
 * core's [SecretsRepository] rather than through [SecureStore] directly: docs/05 "코어 구현 메모"
 * makes that the one entry point the core owns.
 */
class SecretStore(private val secrets: SecretsRepository) : Secrets {

    override suspend fun names(): List<String> = secrets.names()

    override suspend fun put(name: String, value: String) = secrets.put(name, value)

    override suspend fun delete(name: String) = secrets.delete(name)
}

/**
 * docs/05 "시크릿": the name is the only part of a secret that ever leaves this device — it is what
 * a step's `secretRef` holds — so it obeys the same `^[a-z][a-z0-9_]{0,31}$` the parser enforces on
 * `secretRef` (docs/02).
 */
object SecretName {
    private val RULE = Regex("^[a-z][a-z0-9_]{0,31}$")

    /** Null when the name is usable, otherwise the string that says why it is not. */
    fun problem(name: String, existing: Collection<String> = emptyList()): Str? = when {
        name.isEmpty() -> Str.SECRET_NAME_EMPTY
        !RULE.matches(name) -> Str.SECRET_NAME_INVALID
        name in existing -> Str.SECRET_NAME_TAKEN
        else -> null
    }
}
