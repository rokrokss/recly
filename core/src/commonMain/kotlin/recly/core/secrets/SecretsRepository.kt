package recly.core.secrets

import recly.core.platform.CoreDeps
import recly.core.platform.SecureStore

/**
 * The `secrets` namespace of the device's [SecureStore], as everything above the core sees it
 * (docs/05 "시크릿": names travel in `workflows.json`, values never leave the device).
 *
 * Values are per-device and stay per-device — there is no sync and no export carries them, so a new
 * device is a device whose `secretRef`s have nothing behind them until its user types them in
 * ("이 기기에 키 없음"). Shells write through here rather than through [SecureStore] directly, so
 * that the one namespace the core owns has one entry point.
 */
class SecretsRepository(private val deps: CoreDeps) {
    /**
     * A screen asking which keys this device holds has to be told the store would not answer rather
     * than shown an empty list, or a `secretRef` that does resolve gets the "no key on this device"
     * badge. So the shell's throw travels: `@Throws`, because Kotlin/Native terminates the process
     * for an undeclared exception out of an exported suspend function, and this one is exported and
     * does throw — a Keychain refusing to be read (`errSecMissingEntitlement`,
     * `errSecInteractionNotAllowed`) is the case that found it.
     */
    @Throws(Throwable::class)
    suspend fun names(): List<String> = deps.secureStore.names(SecureStore.SECRETS).sorted()

    /** The same crossing, and the same reason for `@Throws`: a value that would not be read is not none. */
    @Throws(Throwable::class)
    suspend fun get(name: String): String? =
        deps.secureStore.get(SecureStore.SECRETS, name)?.decodeToString()

    @Throws(Throwable::class)
    suspend fun put(name: String, value: String) {
        deps.secureStore.put(SecureStore.SECRETS, name, value.encodeToByteArray())
    }

    @Throws(Throwable::class)
    suspend fun delete(name: String) {
        deps.secureStore.delete(SecureStore.SECRETS, name)
    }
}
