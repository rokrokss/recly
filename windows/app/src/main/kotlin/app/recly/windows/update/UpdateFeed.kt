package app.recly.windows.update

import app.recly.windows.auth.OAuthConfig
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * docs/14 "Updates": what one release offers the desktops — `update.json`, written and signed by
 * `scripts/desktop-update.swift` and attached to every app release. The Mac reads the same file
 * (`apple/RecKit/…/Update/UpdateFeed.swift`); this shell reads only its own half of it.
 */
@Serializable
internal data class UpdateManifest(
    val schema: Int,
    /** The display version every platform shares: what the user is told. */
    val version: String,
    /** Absent when the release has no MSI. */
    val windows: WindowsPackage? = null,
) {
    @Serializable
    data class WindowsPackage(
        /** The MSI's own MAJOR.MINOR.BUILD, which is what Windows upgrades by (`OAuthConfig.BUILD`). */
        val installerVersion: String,
        val url: String,
        val size: Long,
        val sha256: String,
    )
}

/** A newer MSI than this install, as the signed feed describes it. */
data class AvailableUpdate(
    /** The display version, for the sentence that offers it. */
    val version: String,
    val installerVersion: String,
    val url: String,
    val size: Long,
    /** Lowercase hex. */
    val sha256: String,
)

/**
 * Why a check or an install stopped — the line Settings says, and the `reason` of `shell.update.*`. The Mac's
 * `UpdateFailure` has the same raw values.
 */
enum class UpdateFailure(val reason: String) {
    /** The feed could not be fetched, its signature did not verify, or it is not a feed this build reads. */
    CHECK("check"),

    /** The MSI could not be fetched. */
    DOWNLOAD("download"),

    /** The MSI's size or SHA-256 is not the signed feed's. */
    SIGNATURE("signature"),

    /** The MSI could not be written to disk, or the installer could not be started. */
    INSTALL("install"),
}

/** A step of a check or an install that stopped, with a [detail] for the log that names no user, path or URL. */
class UpdateException(val failure: UpdateFailure, val detail: String, cause: Throwable? = null) :
    Exception("${failure.reason}: $detail", cause)

/** An HTTP answer other than 200 — a release with no `update.json` is a 404, which is a failure and not "up to date". */
class HttpStatusException(val status: Int) : IOException("HTTP $status")

/**
 * docs/15 "Desktop updates": the one way an update reaches this PC — a plain GET, with the app and its version as the
 * user agent and nothing about the user, the device or the install: no cookies, no identifiers. GitHub answers a
 * release asset with a redirect to its asset host, which `HttpURLConnection` follows (https to https).
 */
class UpdateHttp(private val userAgent: String = USER_AGENT) {

    /** Opens [url] and hands [read] its body and its announced length (-1 when there is none). */
    fun <T> get(url: String, read: (body: InputStream, length: Long) -> T): T {
        val connection = URI(url).toURL().openConnection()
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", userAgent)
        try {
            if (connection is HttpURLConnection) {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) throw HttpStatusException(status)
            }
            return connection.getInputStream().use { read(it, connection.contentLengthLong) }
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
    }

    companion object {
        /** Only the app and its version (docs/15 "Desktop updates"). */
        val USER_AGENT: String = "Recly/${OAuthConfig.APP_VERSION} (Windows)"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}

/**
 * Fetches `update.json` and `update.json.sig`, verifies the Ed25519 signature over the file's exact bytes before
 * reading a field of it, and answers whether it offers a newer MSI than this install.
 */
class UpdateFeed(
    private val feedUrl: String = FEED_URL,
    private val publicKey: String = PUBLIC_KEY,
    private val http: UpdateHttp = UpdateHttp(),
) {
    /** The newer MSI, or null when [currentInstallerVersion] is the newest the feed offers Windows. Blocks. */
    fun check(currentInstallerVersion: String): AvailableUpdate? {
        val body = fetch(feedUrl)
        val signature = fetch("$feedUrl.sig")
        return newer(body, signature, publicKey, currentInstallerVersion)
    }

    private fun fetch(url: String): ByteArray {
        val bytes = try {
            http.get(url) { body, _ -> body.readNBytes(FEED_LIMIT + 1) }
        } catch (e: HttpStatusException) {
            throw UpdateException(UpdateFailure.CHECK, "http ${e.status}", e)
        } catch (e: IOException) {
            throw UpdateException(UpdateFailure.CHECK, e::class.simpleName ?: "io", e)
        }
        if (bytes.size > FEED_LIMIT) throw UpdateException(UpdateFailure.CHECK, "too large")
        return bytes
    }

    companion object {
        /**
         * The public half of `~/.recly/update-signing.key`. The Mac carries the same key, and the release script
         * refuses a key that both do not carry — it looks for this literal, in its quotes.
         */
        const val PUBLIC_KEY: String = "16YvI/g8VLFPMd5TPNTcsbmU7Iy9kqZB8OsvTr4u/mE="

        /** `releases/latest` is the newest release that is neither a draft nor a pre-release. */
        const val FEED_URL: String = "https://github.com/rokrokss/recly/releases/latest/download/update.json"

        private const val SCHEMA = 1

        /** The feed is a few hundred bytes; anything this size is not one. */
        private const val FEED_LIMIT = 64 * 1024

        private val JSON = Json { ignoreUnknownKeys = true }
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val INSTALLER_VERSION = Regex("""\d{1,9}\.\d{1,9}\.\d{1,9}""")

        /** The X.509 header of an Ed25519 public key: what turns the raw 32 bytes into a key the JDK takes. */
        private val ED25519_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

        /** The pure half of [check]: verify, decode, compare. */
        internal fun newer(body: ByteArray, signature: ByteArray, publicKey: String, current: String): AvailableUpdate? {
            if (!verify(body, signature, publicKey)) throw UpdateException(UpdateFailure.CHECK, "signature")
            val manifest = try {
                JSON.decodeFromString(UpdateManifest.serializer(), body.decodeToString())
            } catch (e: SerializationException) {
                throw UpdateException(UpdateFailure.CHECK, "format", e)
            } catch (e: IllegalArgumentException) {
                throw UpdateException(UpdateFailure.CHECK, "format", e)
            }
            if (manifest.schema != SCHEMA) throw UpdateException(UpdateFailure.CHECK, "schema ${manifest.schema}")
            val windows = manifest.windows ?: return null
            val sha256 = windows.sha256.lowercase()
            if (!INSTALLER_VERSION.matches(windows.installerVersion) || windows.size <= 0 || !SHA256.matches(sha256)) {
                throw UpdateException(UpdateFailure.CHECK, "package")
            }
            if (!isNewer(windows.installerVersion, current)) return null
            return AvailableUpdate(
                version = manifest.version,
                installerVersion = windows.installerVersion,
                url = windows.url,
                size = windows.size,
                sha256 = sha256,
            )
        }

        /** [signature] is base64 of the raw 64-byte signature, a trailing newline allowed; [publicKey] base64 of the raw 32 bytes. */
        internal fun verify(body: ByteArray, signature: ByteArray, publicKey: String): Boolean {
            return try {
                val raw = Base64.getDecoder().decode(signature.decodeToString().trim())
                val key = Base64.getDecoder().decode(publicKey)
                if (raw.size != 64 || key.size != 32) return false
                val signer = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(ED25519_PREFIX + key))
                Signature.getInstance("Ed25519").run {
                    initVerify(signer)
                    update(body)
                    verify(raw)
                }
            } catch (e: IllegalArgumentException) {
                false
            } catch (e: GeneralSecurityException) {
                false
            }
        }

        /**
         * Whether [candidate] is a later MSI than [current]: MAJOR.MINOR.BUILD compared as numbers, field by field — so
         * `0.3.10` is later than `0.3.9`. A version that is not three numbers is never later.
         */
        fun isNewer(candidate: String, current: String): Boolean {
            val next = parts(candidate) ?: return false
            val installed = parts(current) ?: return false
            next.zip(installed).forEach { (a, b) -> if (a != b) return a > b }
            return false
        }

        private fun parts(version: String): List<Int>? =
            version.takeIf(INSTALLER_VERSION::matches)?.split('.')?.map(String::toInt)
    }
}
