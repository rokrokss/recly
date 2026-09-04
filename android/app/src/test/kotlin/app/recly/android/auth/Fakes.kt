package app.recly.android.auth

import recly.core.platform.SecureStore

/**
 * The one flat map `AndroidSecureStore` is on a device, minus the Keystore — the namespace lives in
 * the key there too, so a test that gets the prefix wrong fails here the same way.
 */
internal class FakeSecureStore : SecureStore {
    private val entries = mutableMapOf<String, ByteArray>()

    override suspend fun get(ns: String, key: String): ByteArray? = entries["$ns/$key"]

    override suspend fun put(ns: String, key: String, value: ByteArray) {
        entries["$ns/$key"] = value
    }

    override suspend fun delete(ns: String, key: String) {
        entries.remove("$ns/$key")
    }

    override suspend fun names(ns: String): List<String> =
        entries.keys.filter { it.startsWith("$ns/") }.map { it.removePrefix("$ns/") }
}
