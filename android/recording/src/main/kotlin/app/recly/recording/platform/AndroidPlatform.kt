// EncryptedSharedPreferences and MasterKey are deprecated upstream; ADR-008 keeps them for M2/M3.
@file:Suppress("DEPRECATION")
@file:OptIn(ExperimentalTime::class)

package app.recly.recording.platform

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import recly.core.platform.Clock
import recly.core.platform.SecureStore
import kotlin.time.Clock as TimeClock

/*
 * The three pieces of `CoreDeps` the phone and the watch build identically. They live in this
 * module because it is the one both shells already share (docs/11 W1) — a store that reads the
 * same file under the same key on both is one implementation or it is a bug waiting to happen.
 */

/**
 * [SecureStore] on EncryptedSharedPreferences (Keystore-wrapped AES). Deprecated upstream; ADR-008
 * leaves the DataStore + Keystore replacement to a later lane and M2 ships this.
 *
 * The prefs file is built lazily on [io]: `EncryptedSharedPreferences.create` generates or unwraps
 * the master key and that is not work for the main thread.
 *
 * On the watch the only thing it ever holds is the device UUID — ADR-002 keeps OAuth material and
 * webhook secrets on the phone — and it is still the Keystore-backed store there, because the day
 * something else needs putting in it must not be a decision anyone has to remember to revisit.
 */
class AndroidSecureStore(
    private val context: Context,
    private val io: CoroutineDispatcher,
) : SecureStore {

    @Volatile private var prefs: SharedPreferences? = null

    override suspend fun get(ns: String, key: String): ByteArray? = withContext(io) {
        prefs().getString(entry(ns, key), null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    override suspend fun put(ns: String, key: String, value: ByteArray): Unit = withContext(io) {
        prefs().edit()
            .putString(entry(ns, key), Base64.encodeToString(value, Base64.NO_WRAP))
            .commit()
        Unit
    }

    override suspend fun delete(ns: String, key: String): Unit = withContext(io) {
        prefs().edit().remove(entry(ns, key)).commit()
        Unit
    }

    /**
     * The keys of one namespace, which is what the secrets screen lists and what
     * [SecureStore.clear] deletes. Not a separate index: EncryptedSharedPreferences decrypts key
     * names on `getAll`, so the store is its own index and cannot drift from itself.
     */
    override suspend fun names(ns: String): List<String> = withContext(io) {
        val prefix = entry(ns, "")
        prefs().all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    private fun prefs(): SharedPreferences = prefs ?: synchronized(this) {
        prefs ?: create().also { prefs = it }
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** One flat prefs file, so the namespace has to live in the key. */
    private fun entry(ns: String, key: String) = "$ns/$key"

    private companion object {
        const val FILE = "rec_secure"
    }
}

/** docs/01: a UUID v4 minted at install time; a reinstall wipes the store and gets a new one. */
suspend fun deviceId(store: SecureStore): String {
    store.get(NS_DEVICE, KEY_ID)?.decodeToString()?.let { return it }
    return UUID.randomUUID().toString().also { store.put(NS_DEVICE, KEY_ID, it.encodeToByteArray()) }
}

private const val NS_DEVICE = "device"
private const val KEY_ID = "id"

object SystemClock : Clock {
    override fun now(): Instant = TimeClock.System.now()
}
