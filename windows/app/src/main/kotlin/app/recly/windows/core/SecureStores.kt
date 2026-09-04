@file:OptIn(ExperimentalTime::class)

package app.recly.windows.core

import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import recly.core.platform.SecureStore

object SecureStores {
    /**
     * Windows Credential Manager (docs/05 "시크릿", docs/06) where there is one, the development
     * stub everywhere else. The choice is made once, here, so nothing above it has to ask which
     * machine it is on.
     */
    fun create(dataDir: Path, fileSystem: FileSystem, io: CoroutineDispatcher): SecureStore =
        if (Host.isWindows) {
            WindowsCredentialStore(io)
        } else {
            DevFileSecureStore(fileSystem, dataDir / "dev-secure-store.json", io)
        }
}

/**
 * **Development only.** A JSON file next to the database, values base64-encoded and not encrypted
 * at all: it exists so the app can be built and run on the macOS development host (lane M6-L1
 * "환경 제약"), and it is never reached on Windows, where [WindowsCredentialStore] is chosen
 * instead. A refresh token in here is as safe as the user's home directory and no safer.
 */
class DevFileSecureStore(
    private val fileSystem: FileSystem,
    private val file: Path,
    private val io: CoroutineDispatcher,
) : SecureStore {

    private val mutex = Mutex()
    private val json = Json { prettyPrint = true }

    override suspend fun get(ns: String, key: String): ByteArray? = locked {
        read()[entry(ns, key)]?.let { decode(it) }
    }

    override suspend fun put(ns: String, key: String, value: ByteArray): Unit = locked {
        write(read() + (entry(ns, key) to encode(value)))
    }

    override suspend fun delete(ns: String, key: String): Unit = locked {
        write(read() - entry(ns, key))
    }

    override suspend fun names(ns: String): List<String> = locked {
        val prefix = entry(ns, "")
        read().keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.sorted()
    }

    private suspend fun <T> locked(body: () -> T): T = withContext(io) { mutex.withLock { body() } }

    private fun read(): Map<String, String> =
        if (!fileSystem.exists(file)) {
            emptyMap()
        } else {
            fileSystem.read(file) { json.decodeFromString<Map<String, String>>(readUtf8()) }
        }

    private fun write(entries: Map<String, String>) {
        file.parent?.let { fileSystem.createDirectories(it) }
        fileSystem.write(file) { writeUtf8(json.encodeToString(entries)) }
    }

    /** One flat file, so the namespace has to live in the key — as it does in the phone's prefs. */
    private fun entry(ns: String, key: String) = "$ns/$key"

    private fun encode(value: ByteArray) = java.util.Base64.getEncoder().encodeToString(value)

    private fun decode(value: String) = java.util.Base64.getDecoder().decode(value)
}
