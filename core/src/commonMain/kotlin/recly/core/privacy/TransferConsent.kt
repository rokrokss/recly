@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.privacy

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.ktor.http.URLBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8
import recly.core.db.RecDatabase
import recly.core.ids.Ulid
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.model.Step
import recly.core.model.Workflow
import recly.core.model.recJson
import recly.core.platform.CoreDeps
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult
import recly.core.platform.Transport
import recly.core.transcribe.SttProviders

/** docs/15: permission belongs to a destination and purpose, never a workflow or an API key. */
@Serializable
data class TransferTarget(
    val kind: String,
    val provider: String,
    val endpoint: String,
    val disclosureVersion: Int = TransferTargets.DISCLOSURE_VERSION,
) {
    val id: String get() = "$disclosureVersion\n$kind\n$provider\n$endpoint".encodeUtf8().sha256().hex()
}

/** The configured base endpoint, not a provider's changing upload URL or transcription job id. */
object TransferTargets {
    // Bump only when the disclosed data or purpose materially changes, never for copy edits.
    const val DISCLOSURE_VERSION = 1

    fun forWorkflow(workflow: Workflow): List<TransferTarget> =
        workflow.steps.mapNotNull(::forStep).distinctBy { it.id }

    fun forStep(step: Step): TransferTarget? = when (step) {
        is Step.DriveUpload -> null // Google OAuth already authorizes Drive access.
        is Step.Webhook -> canonical(step.url, trimPath = false)?.let {
            TransferTarget("webhook", "", it)
        }
        is Step.Transcribe -> endpoint(step)
            ?.let { canonical(it, trimPath = true) }
            ?.let { TransferTarget("transcribe", step.provider, it) }
    }

    /** Only these runners use invokeUrl. Ignored configuration must never mislabel the recipient. */
    private fun endpoint(step: Step.Transcribe): String? = when (step.provider) {
        "clova", "azure", "openai", "groq", "together", "mistral", "speechmatics" ->
            step.invokeUrl ?: SttProviders.defaultEndpoint(step.provider)
        else -> SttProviders.defaultEndpoint(step.provider)
    }

    private fun canonical(raw: String, trimPath: Boolean): String? = runCatching {
        val url = URLBuilder(raw.trim()).apply { fragment = "" }.build()
        require(url.protocol.name in setOf("http", "https") && url.host.isNotEmpty())
        val value = url.toString()
        if (trimPath && url.encodedQuery.isEmpty()) value.trimEnd('/') else value
    }.getOrNull()

    internal fun valid(target: TransferTarget): Boolean =
        target.disclosureVersion == DISCLOSURE_VERSION && when (target.kind) {
            "webhook" -> target.provider.isEmpty() && canonical(target.endpoint, false) == target.endpoint
            "transcribe" -> SttProviders.create(target.provider) != null &&
                canonical(target.endpoint, true) == target.endpoint
            else -> false
        }
}

@Serializable
private data class TransferGrant(val target: TransferTarget, val deviceProof: String)

/**
 * docs/15: durable grants in the local database, bound to a device-only Keychain marker on iOS.
 * Neither workflow exports nor a marker surviving uninstall can restore the deleted grants.
 * The iOS shell opts in; other shells keep their existing policy while sharing the wire types.
 */
class TransferConsents(private val db: RecDatabase, private val deps: CoreDeps) {
    private val writes = Mutex()
    val enabled: Boolean get() = deps.requireTransferConsent
    private val queries get() = db.recQueries

    @Throws(Throwable::class)
    suspend fun approved(): List<TransferTarget> = withContext(deps.io) {
        if (!enabled) return@withContext emptyList()
        val proof = deviceProof() ?: return@withContext emptyList()
        queries.kvSelectPrefix(PREFIX).executeAsList().mapNotNull { row ->
            decode(row.value_, proof)?.takeIf { row.key == PREFIX + it.id }
        }
    }

    fun observe(): Flow<List<TransferTarget>> = queries.kvSelectPrefix(PREFIX).asFlow()
        .mapToList(deps.io).map { rows ->
            val proof = if (enabled) deviceProof() else null
            if (proof == null) emptyList() else rows.mapNotNull { row ->
                decode(row.value_, proof)?.takeIf { row.key == PREFIX + it.id }
            }
        }.distinctUntilChanged()

    @Throws(Throwable::class)
    suspend fun missing(targets: List<TransferTarget>): List<TransferTarget> {
        if (!enabled) return emptyList()
        val allowed = approved().map { it.id }.toSet()
        return targets.distinctBy { it.id }.filter { it.id !in allowed }
    }

    /** Only the explicit UI action calls this; saving/importing a workflow does not imply consent. */
    @Throws(Throwable::class)
    suspend fun grant(targets: List<TransferTarget>): Unit = withContext(deps.io) {
        if (targets.isEmpty()) return@withContext
        require(enabled && targets.all(TransferTargets::valid)) { "Invalid transfer destination" }
        writes.withLock {
            val proof = deviceProof() ?: Ulid.generate(object : kotlin.time.Clock {
                override fun now() = deps.clock.now()
            }).also {
                deps.secureStore.put(PROOF_NAMESPACE, PROOF_KEY, it.encodeToByteArray())
            }
            db.transaction {
                targets.forEach { queries.kvSet(PREFIX + it.id, recJson.encodeToString(TransferGrant(it, proof))) }
            }
        }
    }

    @Throws(Throwable::class)
    suspend fun revoke(id: String): Unit = withContext(deps.io) {
        queries.kvDelete(PREFIX + id)
    }

    internal suspend fun requireAllowed(step: Step) {
        if (!enabled) return
        if (step is Step.DriveUpload) return
        val target = TransferTargets.forStep(step) ?: throw StepFailure(
            retryable = false, reason = CoreMessage.PROVIDER_ERROR.code("Invalid transfer destination"),
        )
        val allowed = withContext(deps.io) {
            val proof = deviceProof()
            proof != null && queries.kvGet(PREFIX + target.id).executeAsOneOrNull()
                ?.let { decode(it, proof) } == target
        }
        if (!allowed) throw StepFailure(
            retryable = false,
            reason = CoreMessage.TRANSFER_CONSENT_REQUIRED.code(),
            needsConsent = true,
        )
    }

    /** Recheck before every provider request, including polling and multi-request submissions. */
    internal fun guardedDeps(step: Step): CoreDeps {
        if (!enabled || step is Step.DriveUpload) return deps
        val transport = object : Transport {
            override suspend fun execute(plan: HttpPlan): HttpResult {
                requireAllowed(step)
                return deps.transport.execute(plan)
            }
        }
        return CoreDeps(
            deps.clock, deps.logger, deps.secureStore, deps.tokenProvider, transport, deps.fileSystem,
            deps.audio, deps.dataDir, deps.device, deps.appVersion, deps.io, deps.locale,
            requireTransferConsent = true,
        )
    }

    private fun decode(value: String, proof: String): TransferTarget? = runCatching {
        recJson.decodeFromString<TransferGrant>(value)
            .takeIf { it.deviceProof == proof }?.target?.takeIf(TransferTargets::valid)
    }.getOrNull()

    // Apple's store uses AfterFirstUnlockThisDeviceOnly: a restored database on another phone
    // cannot inherit grants. This marker alone is never a grant and is not an API credential.
    private suspend fun deviceProof(): String? =
        deps.secureStore.get(PROOF_NAMESPACE, PROOF_KEY)?.decodeToString()

    private companion object {
        const val PREFIX = "privacy/transfer/"
        const val PROOF_NAMESPACE = "privacy"
        const val PROOF_KEY = "transfer-device"
    }
}
