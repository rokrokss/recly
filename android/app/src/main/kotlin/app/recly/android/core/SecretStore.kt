package app.recly.android.core

import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import recly.core.secrets.SecretsRepository
import recly.core.webhook.Signer

/**
 * The `secrets` namespace of the device's secure store, as the settings UI sees it (docs/05
 * "시크릿": names travel in `workflows.json`, values never leave this device).
 *
 * Every read and write goes through the core's [SecretsRepository] and not through
 * `AndroidSecureStore` directly: docs/05 "코어 구현 메모" makes that the one entry point the core
 * owns.
 *
 * The listing is still the store's own rather than a name index: an index is a second source of
 * truth that a restore, a crash between two writes, or a future migration can silently
 * desynchronise — and a `secretRef` pointing at a name the store does not really have is exactly
 * the failure the "no secret on this device" badge exists to prevent.
 */
class SecretStore(private val secrets: SecretsRepository) {

    suspend fun names(): List<String> = secrets.names()

    suspend fun put(name: String, value: String) = secrets.put(name, value)

    suspend fun delete(name: String) = secrets.delete(name)

    /**
     * docs/04's `whsec_…`. `SecureRandom`, not `Random.Default`: this is a signing key, and Kotlin's
     * default generator is not a cryptographic one.
     */
    fun generate(): String = Signer.generateSecret(SecureRandom().asKotlinRandom())
}
