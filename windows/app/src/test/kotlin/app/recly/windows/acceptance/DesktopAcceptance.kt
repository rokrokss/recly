@file:OptIn(ExperimentalTime::class)

package app.recly.windows.acceptance

import app.recly.windows.auth.GoogleAuth
import app.recly.windows.auth.OAuthConfig
import app.recly.windows.auth.SignInResult
import app.recly.windows.auth.TokenEndpoint
import app.recly.windows.core.AppGraph
import app.recly.windows.core.AppModule
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.helper.HelperClient
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.text
import app.recly.windows.record.RecordingOutcome
import app.recly.windows.record.WindowsRecorder
import app.recly.windows.record.completeRecording
import app.recly.windows.workflow.StepEdit
import app.recly.windows.workflow.WorkflowEdit
import app.recly.windows.workflow.with
import app.recly.windows.workflow.without
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import recly.core.ReclyCore
import recly.core.ids.Ulid
import recly.core.job.EnqueueResult
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.StepRun
import recly.core.platform.HttpPlan
import recly.core.platform.Transport
import recly.core.sync.SaveResult
import kotlin.time.Clock as TimeClock

/**
 * docs/20 "인수 시나리오 · M6 Windows 1", run on the macOS development host with only the audio
 * faked: a real Google sign-in, a real Drive upload and a real signed webhook to the local receiver
 * (`scripts/webhook-receiver.mjs`). Everything between the consent screen and the receiver is the
 * app's own — `AppModule`, `WindowsRecorder`, `completeRecording`, `ReclyCore.runDueJobs`.
 *
 * It is a test only so that it can be run with one Gradle command and reuse the module's classpath;
 * it is not a unit test and is **skipped** unless `-Drecly.acceptance=1` is given.
 *
 * ```
 * node scripts/webhook-receiver.mjs --port 8787 --secret "$SECRET"      # a second terminal
 * JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :windows:app:test --rerun \
 *   --tests '*DesktopAcceptance*' -Drecly.acceptance=1 \
 *   -Drecly.acceptance.dataDir=/tmp/recly-accept -Drecly.acceptance.authUrlFile=/tmp/recly-auth-url \
 *   -Drecly.acceptance.webhookSecret="$SECRET" -Drecly.acceptance.webhookUrl=http://127.0.0.1:8787/hook -i
 * ```
 *
 * The consent screen is **not** opened here. The browser seam prints `AUTH_URL=…` and writes it to
 * `recly.acceptance.authUrlFile`; whoever is driving the run opens it, and the loopback receiver
 * (`LoopbackReceiver`, an ephemeral port on 127.0.0.1) catches the redirect. Every step prints one
 * `ACCEPT <step> …` line of evidence, and any failure fails the test — which is the non-zero exit.
 *
 * The Drive files are left in place on purpose: they are the evidence. The workflow is not: it
 * lives in the appdata document every one of the user's devices syncs, so a `finally` takes it back
 * out and pushes — a cleanup that cannot verify the push fails the run — along with the secret this
 * run stored. `-Drecly.acceptance.removeStaleNamed=인수`
 * additionally clears same-named workflows left by runs that predate that cleanup.
 */
class DesktopAcceptance {

    @Test
    fun `docs20 M6 1 — sign-in → record → Drive → webhook`() = runBlocking {
        if (prop(GATE) != "1") {
            println("ACCEPT skipped — no -D$GATE=1")
            return@runBlocking
        }
        val dataDir = File(required(DATA_DIR))
        val webhookUrl = required(WEBHOOK_URL)
        val webhookSecret = required(WEBHOOK_SECRET)
        val authUrlFile = prop(AUTH_URL_FILE)?.let { File(it) }
        val authTimeout = (prop(AUTH_TIMEOUT_SEC)?.toLongOrNull() ?: DEFAULT_AUTH_TIMEOUT_SEC).seconds

        // (1) the core, in a data directory of its own -------------------------------------------
        check(!File(dataDir, "rec.db").exists()) {
            "$DATA_DIR '$dataDir' already holds a rec.db — run again with a new directory"
        }
        dataDir.mkdirs()
        val graph = AppModule.build(dataDir = dataDir.absolutePath.toPath())
        val core = graph.core
        val deps = core.deps
        accept("open", "dataDir=$dataDir", "deviceId=${deps.device.deviceId}", "device=${deps.device.name}")

        // (2) sign-in (docs/06) — the consent screen is somebody else's browser --------------------
        check(!OAuthConfig.isPlaceholder) {
            "google.desktopClientId/google.desktopClientSecret are missing (windows/app/README.md)"
        }
        val auth = GoogleAuth(
            tokens = graph.tokens,
            endpoint = TokenEndpoint(deps.transport),
            logger = deps.logger,
            browser = { url ->
                println("AUTH_URL=$url")
                authUrlFile?.writeText(url + "\n")
            },
            consentTimeout = authTimeout,
        )
        when (val result = auth.signIn()) {
            SignInResult.Ok -> Unit
            SignInResult.NoClient -> error("signIn: no client id")
            is SignInResult.Failed ->
                error("signIn: ${result.reason.text(StringTable.of(StringTable.BASE))}")
        }
        accept("signIn", "account=${mask(account(deps.transport, graph.tokens.accessToken()))}")

        // (3)–(7) run under one `try`: from here on the run owns things the user's other devices
        // would inherit — a secret in this device's store, a workflow in the published document —
        // and (8) has to take them back out whether or not the rest of the run got anywhere.
        var workflowId: String? = null
        var failed = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // (3) the workflow the recording will run (docs/02) -------------------------------------
            graph.secrets.put(SECRET_REF, webhookSecret)
            val edit = WorkflowEdit(
                id = Ulid.generate(TimeClock.System),
                name = "인수",
                minDurationSec = "0",
                steps = listOf(
                    // The docs/05 default folder rule (`recly/{{yyyy}}/{{yyyy}}-{{MM}}`) is the step's own.
                    StepEdit.Drive(id = UPLOAD_STEP),
                    StepEdit.Hook(id = HOOK_STEP, url = webhookUrl, secretRef = SECRET_REF),
                ),
            )
            workflowId = edit.id
            when (val saved = core.workflows.save(core.workflows.current().with(edit, deps.clock.now()))) {
                is SaveResult.Invalid -> error("workflow: ${saved.errors.joinToString("; ")}")
                is SaveResult.Saved -> Unit
            }
            accept(
                "workflow",
                "id=${edit.id}",
                "name=${edit.name}",
                "steps=$UPLOAD_STEP,$HOOK_STEP",
                "secretRef=$SECRET_REF",
                "webhook=$webhookUrl",
            )

            // (4) a recording, through the fake capture helper ---------------------------------
            val finalized = CompletableDeferred<RecordingOutcome>()
            val recorder = WindowsRecorder(
                core = core,
                scope = scope,
                helper = {
                    HelperClient(
                        // `write`: this recording is really uploaded, so the parts have to be on disk.
                        FakeHelperCommand.command("parts=1", "sec=3.0", "write"),
                        deps.io,
                        deps.logger,
                    )
                },
                onFinalized = { finalized.complete(it) },
            )
            val recordingId = recorder.start(workflowId = edit.id)
                ?: error("record: could not start the recording")
            delay(RECORD_MS)
            recorder.stop()
            val outcome = withTimeout(FINALIZE_MS) { finalized.await() }
            val enqueued = completeRecording(core, outcome.recordingId, TITLE)
            val jobId = (enqueued as? EnqueueResult.Enqueued)?.jobId
                ?: error("enqueue: ${enqueued::class.simpleName}")
            accept(
                "record",
                "recordingId=$recordingId",
                "title=$TITLE",
                "parts=${outcome.parts}",
                "durationSec=${outcome.durationSec}",
                "jobId=$jobId",
            )

            // (5) the executor, until the job settles (docs/10 "잡 상태 머신") ---------------------------
            var job: Job = requireJob(core, jobId)
            var passes = 0
            val deadline = System.nanoTime() + JOB_TIMEOUT_MS * 1_000_000
            while (job.status != JobStatus.DONE && job.status != JobStatus.FAILED) {
                check(System.nanoTime() < deadline) {
                    "job $jobId: did not settle within ${JOB_TIMEOUT_MS / 1000}s (${job.status}) " +
                        describe(core.jobs.steps(jobId))
                }
                core.runDueJobs()
                passes++
                job = requireJob(core, jobId)
                if (job.status == JobStatus.DONE || job.status == JobStatus.FAILED) break
                delay(POLL_MS)
            }
            val steps = core.jobs.steps(jobId)
            check(job.status == JobStatus.DONE) { "job $jobId: ${job.status} — ${describe(steps)}" }
            accept("job", "id=$jobId", "status=${job.status}", "passes=$passes", "steps=${describe(steps)}")

            // (6) what the two steps left behind -------------------------------------------------------
            val upload = steps.first { it.stepId == UPLOAD_STEP }.output
                ?: error("$UPLOAD_STEP: output is empty")
            val files = upload.getValue("files").jsonArray.map { it.jsonObject }
            accept(
                "drive",
                "fileIds=${files.joinToString(",") { it.string("fileId") }}",
                "folder=${upload.string("path")}",
                "folderId=${upload.string("folderId")}",
                "link=${upload["folderWebViewLink"]?.jsonPrimitive?.content ?: "-"}",
            )
            val hook = steps.first { it.stepId == HOOK_STEP }
            accept(
                "webhook",
                "status=${hook.output?.get("status")?.jsonPrimitive?.content ?: "-"}",
                // `attempts` counts failures (docs/10), so the delivery that succeeded is the next one.
                "attempt=${hook.attempts + 1}",
                "webhookId=${hook.id}",
            )

            // (7) Drive itself, not our own record of it ------------------------------------------------
            val token = graph.tokens.accessToken()
            for (file in files) {
                val id = file.string("fileId")
                val fetched = getJson(deps.transport, "$DRIVE_FILES$id?fields=name,size,parents", token)
                accept(
                    "drive.verify",
                    "fileId=$id",
                    "name=${fetched.string("name")}",
                    "size=${fetched.string("size")}",
                    "parents=${fetched.getValue("parents").jsonArray.joinToString(",") { it.jsonPrimitive.content }}",
                )
            }
            accept("done", "recordingId=$recordingId", "jobId=$jobId", "files=${files.size}")
        } catch (t: Throwable) {
            failed = true
            throw t
        } finally {
            // (8) cleanup — this run's workflow is evidence in the log, not something the user's
            // other devices should inherit. It goes back out of the appdata document even when a
            // step above failed, together with the secret this run put in the store.
            scope.cancel()
            val errors = cleanup(core, graph, workflowId)
            // A run that cleaned up after itself badly is a failed run — but only the first failure
            // is worth raising: throwing here on top of one would replace it with its own aftermath.
            if (errors.isNotEmpty() && !failed) error("cleanup: ${errors.joinToString("; ")}")
        }
    }

    // --- plumbing -----------------------------------------------------------------------------------

    private fun accept(step: String, vararg fields: String) {
        println("ACCEPT $step ${fields.joinToString(" ")}")
    }

    /**
     * Takes this run's workflow back out of this device's document — a run that left "인수" in it
     * would still be in the picker the next time somebody opened the editor. `save` is the same
     * call the editor's delete makes.
     *
     * [REMOVE_STALE_NAMED] takes out any *other* workflow of that name as well, for runs that
     * predate this cleanup and left one behind.
     *
     * Setup may have failed halfway, so [workflowId] can be null and the secret may never have been
     * stored; the document and the secret are cleaned independently so one failure cannot keep the
     * other from being tried. Returns what went wrong — empty when everything came out.
     */
    private suspend fun cleanup(core: ReclyCore, graph: AppGraph, workflowId: String?): List<String> {
        val errors = mutableListOf<String>()
        val fields = mutableListOf("workflowRemoved=${workflowId ?: "-"}")

        runCatching {
            val document = core.workflows.current()
            val stale = prop(REMOVE_STALE_NAMED)?.let { name ->
                document.workflows.filter { it.name == name && it.id != workflowId }.map { it.id }
            }.orEmpty()
            fields += "stale=${stale.joinToString(",").ifEmpty { "-" }}"
            val gone = (listOfNotNull(workflowId) + stale).filter { id -> document.workflows.any { it.id == id } }
            if (gone.isEmpty()) return@runCatching
            when (val saved = core.workflows.save(gone.fold(document) { doc, id -> doc.without(id) })) {
                is SaveResult.Invalid -> error("save: ${saved.errors.joinToString("; ")}")
                is SaveResult.Saved -> Unit
            }
        }.onFailure { errors += "workflow: ${it.message}" }

        // Its own step: the secret is on this device only, and it has to go even when the document
        // could not be written. Deleting one that was never stored is not an error.
        runCatching { graph.secrets.delete(SECRET_REF) }
            .onSuccess { fields += "secretRemoved=$SECRET_REF" }
            .onFailure { errors += "secret: ${it.message}" }

        accept("cleanup", *(fields + errors.map { "failed=$it" }).toTypedArray())
        return errors
    }

    private suspend fun requireJob(core: ReclyCore, jobId: String): Job =
        core.jobs.list().firstOrNull { it.id == jobId } ?: error("job $jobId is gone")

    private fun describe(steps: List<StepRun>): String =
        steps.joinToString(" ") { "${it.stepId}=${it.status}${it.lastError?.let { e -> "($e)" } ?: ""}" }

    /**
     * `drive.file` is enough for `about.get`, which is the only place this grant can be asked whose
     * account it is — the app never asks for a profile scope (ADR-009).
     */
    private suspend fun account(transport: Transport, token: String): String = runCatching {
        getJson(transport, "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)", token)
            .getValue("user").jsonObject.string("emailAddress")
    }.getOrElse { "unknown(${it.message})" }

    private suspend fun getJson(transport: Transport, url: String, token: String): JsonObject {
        val result = transport.execute(
            HttpPlan(method = "GET", url = url, headers = mapOf("authorization" to "Bearer $token")),
        )
        check(result.status == 200) { "GET $url → ${result.status} ${result.body.decodeToString().take(200)}" }
        return json.decodeFromString<JsonObject>(result.body.decodeToString())
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    /** docs/20 evidence, not an address book: `someone@example.com` → `so***@example.com`. */
    private fun mask(email: String): String {
        val at = email.indexOf('@')
        if (at <= 0) return email
        return email.take(minOf(2, at)) + "***" + email.substring(at)
    }

    private fun prop(name: String): String? = System.getProperty(name)?.takeIf { it.isNotBlank() }

    private fun required(name: String): String = prop(name) ?: error("-D$name is required")

    private companion object {
        const val GATE = "recly.acceptance"
        const val DATA_DIR = "recly.acceptance.dataDir"
        const val AUTH_URL_FILE = "recly.acceptance.authUrlFile"
        const val AUTH_TIMEOUT_SEC = "recly.acceptance.authTimeoutSec"
        const val WEBHOOK_SECRET = "recly.acceptance.webhookSecret"
        const val WEBHOOK_URL = "recly.acceptance.webhookUrl"
        const val REMOVE_STALE_NAMED = "recly.acceptance.removeStaleNamed"

        const val SECRET_REF = "acceptance"
        const val UPLOAD_STEP = "upload"
        const val HOOK_STEP = "hook"
        const val TITLE = "인수 테스트"
        const val DRIVE_FILES = "https://www.googleapis.com/drive/v3/files/"

        /** Long enough to find the account chooser and the password manager. */
        const val DEFAULT_AUTH_TIMEOUT_SEC = 600L
        const val RECORD_MS = 3_000L
        const val FINALIZE_MS = 60_000L
        const val JOB_TIMEOUT_MS = 180_000L
        const val POLL_MS = 2_000L

        val json = Json { ignoreUnknownKeys = true }
    }
}
