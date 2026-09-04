package recly.core.platform

/**
 * Keychain / Keystore / DPAPI, provided by the shell. Namespaces: [SECRETS] for webhook signing
 * keys, [TOKENS] for OAuth material.
 */
interface SecureStore {
    suspend fun get(ns: String, key: String): ByteArray?

    suspend fun put(ns: String, key: String, value: ByteArray)

    suspend fun delete(ns: String, key: String)

    /**
     * The keys held in [ns]. Required, with no default: a backend that silently answered "none"
     * would make [clear] silently succeed while leaving the account's secrets on the device.
     */
    suspend fun names(ns: String): List<String>

    companion object {
        const val SECRETS = "secrets"
        const val TOKENS = "tokens"
    }
}

/**
 * "연결 해제" (docs/03): everything in [ns] goes, one [SecureStore.names] entry at a time.
 *
 * An extension and not a member with a default body: a `suspend` member is exported to Swift as a
 * protocol requirement, so every shell's conformer would have to write its own `__clear` for a
 * loop the core already knows how to run.
 *
 * A store that will not be listed fails the sweep rather than emptying nothing quietly: the values
 * are still on the device, and a caller told the namespace was cleared would report a device
 * clean that still holds every key. The failure travels to the shell, whose disconnect keeps the
 * clean-up owed and offers the retry (docs/06 `REVOKED_CLEANUP_OWED`).
 */
suspend fun SecureStore.clear(ns: String) {
    names(ns).forEach { delete(ns, it) }
}
