package app.recly.windows.core

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import recly.core.platform.SecureStore

/**
 * docs/05 "시크릿" · docs/06 Windows: tokens and webhook signing keys live in the Windows Credential
 * Manager, as generic credentials this app owns.
 *
 * JNA rather than `jna-platform`: the platform bundle wraps most of advapi32 but not the credential
 * functions, so the four this needs are declared here. `CredReadW`/`CredWriteW` — the exported
 * names, so no function mapper is involved and what is called is what is written.
 *
 * **Not exercised on the development host.** This lane builds and runs on macOS (M6-L1 "환경 제약"),
 * where [SecureStores] picks [DevFileSecureStore] instead and nothing below is ever loaded. The
 * runtime check belongs to the user's Windows PC / M6-L3.
 */
class WindowsCredentialStore(private val io: CoroutineDispatcher) : SecureStore {

    override suspend fun get(ns: String, key: String): ByteArray? = withContext(io) {
        val holder = PointerByReference()
        if (!lib.CredReadW(WString(target(ns, key)), CRED_TYPE_GENERIC, 0, holder)) {
            val error = Native.getLastError()
            if (error == ERROR_NOT_FOUND) return@withContext null
            throw IllegalStateException("CredRead failed for ${target(ns, key)} (error $error)")
        }
        try {
            val credential = Credential(holder.value)
            credential.CredentialBlob?.getByteArray(0, credential.CredentialBlobSize) ?: ByteArray(0)
        } finally {
            lib.CredFree(holder.value)
        }
    }

    override suspend fun put(ns: String, key: String, value: ByteArray): Unit = withContext(io) {
        // The blob has to outlive the call, so it is a field of the structure and not a temporary.
        val blob = Memory(maxOf(value.size, 1).toLong())
        blob.write(0, value, 0, value.size)
        val credential = Credential().apply {
            Type = CRED_TYPE_GENERIC
            // docs/01: everything this app stores is this machine's, never a roaming profile's.
            Persist = CRED_PERSIST_LOCAL_MACHINE
            TargetName = WString(target(ns, key))
            CredentialBlobSize = value.size
            CredentialBlob = blob
            UserName = WString(Host.APP_ID)
        }
        if (!lib.CredWriteW(credential, 0)) {
            throw IllegalStateException("CredWrite failed for ${target(ns, key)} (error ${Native.getLastError()})")
        }
    }

    override suspend fun delete(ns: String, key: String): Unit = withContext(io) {
        if (!lib.CredDeleteW(WString(target(ns, key)), CRED_TYPE_GENERIC, 0)) {
            val error = Native.getLastError()
            // Deleting what is not there is not a failure: the callers are idempotent by design.
            if (error != ERROR_NOT_FOUND) {
                throw IllegalStateException("CredDelete failed for ${target(ns, key)} (error $error)")
            }
        }
    }

    override suspend fun names(ns: String): List<String> = withContext(io) {
        val count = IntByReference()
        val holder = PointerByReference()
        val prefix = target(ns, "")
        if (!lib.CredEnumerateW(WString("$prefix*"), 0, count, holder)) {
            val error = Native.getLastError()
            if (error == ERROR_NOT_FOUND) return@withContext emptyList()
            throw IllegalStateException("CredEnumerate failed for $prefix* (error $error)")
        }
        try {
            holder.value.getPointerArray(0, count.value)
                .map { Credential(it).TargetName?.toString().orEmpty() }
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
                .sorted()
        } finally {
            lib.CredFree(holder.value)
        }
    }

    /** `app.recly.windows/tokens/refresh_token` — the namespace is part of the credential's name. */
    private fun target(ns: String, key: String) = "${Host.APP_ID}/$ns/$key"

    /**
     * `CREDENTIALW`, field for field and in order — JNA lays the structure out from this list, so
     * the order is the ABI and not a style choice.
     */
    @Structure.FieldOrder(
        "Flags",
        "Type",
        "TargetName",
        "Comment",
        "LastWritten",
        "CredentialBlobSize",
        "CredentialBlob",
        "Persist",
        "AttributeCount",
        "Attributes",
        "TargetAlias",
        "UserName",
    )
    class Credential : Structure {
        @JvmField var Flags: Int = 0

        @JvmField var Type: Int = 0

        @JvmField var TargetName: WString? = null

        @JvmField var Comment: WString? = null

        @JvmField var LastWritten: WinBase.FILETIME = WinBase.FILETIME()

        @JvmField var CredentialBlobSize: Int = 0

        @JvmField var CredentialBlob: Pointer? = null

        @JvmField var Persist: Int = 0

        @JvmField var AttributeCount: Int = 0

        @JvmField var Attributes: Pointer? = null

        @JvmField var TargetAlias: WString? = null

        @JvmField var UserName: WString? = null

        constructor() : super()

        /** What `CredRead`/`CredEnumerate` hand back: memory advapi32 owns until `CredFree`. */
        constructor(pointer: Pointer) : super(pointer) {
            read()
        }
    }

    private interface Advapi32Credentials : Library {
        fun CredReadW(targetName: WString, type: Int, flags: Int, credential: PointerByReference): Boolean

        fun CredWriteW(credential: Credential, flags: Int): Boolean

        fun CredDeleteW(targetName: WString, type: Int, flags: Int): Boolean

        fun CredEnumerateW(
            filter: WString?,
            flags: Int,
            count: IntByReference,
            credentials: PointerByReference,
        ): Boolean

        fun CredFree(buffer: Pointer)
    }

    private companion object {
        /** Loaded on first use, which on any machine but Windows is never. */
        val lib: Advapi32Credentials by lazy {
            Native.load("Advapi32", Advapi32Credentials::class.java)
        }

        const val CRED_TYPE_GENERIC = 1
        const val CRED_PERSIST_LOCAL_MACHINE = 2
        const val ERROR_NOT_FOUND = 1168
    }
}
