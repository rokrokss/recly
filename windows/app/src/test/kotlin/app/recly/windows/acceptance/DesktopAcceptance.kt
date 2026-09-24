@file:OptIn(ExperimentalTime::class)

package app.recly.windows.acceptance

import app.recly.windows.auth.GoogleAuth
import app.recly.windows.auth.OAuthConfig
import app.recly.windows.auth.SignInResult
import app.recly.windows.auth.TokenEndpoint
import app.recly.windows.core.AppModule
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.helper.HelperClient
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.text
import app.recly.windows.record.RecordingOutcome
import app.recly.windows.record.WindowsRecorder
import app.recly.windows.record.completeRecording
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
import recly.core.job.EnqueueResult
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.StepRun
import recly.core.platform.HttpPlan
import recly.core.platform.Transport
import recly.core.processing.ProcessingSaveResult
import recly.core.processing.ProcessingSettings
import recly.core.processing.ProcessingTranscription
import recly.core.processing.TranscriptionMode

/**
 * docs/20 "인수 시나리오 · M6 Windows 1", run on the macOS development host with only the audio
 * faked: a real Google sign-in and a real Drive upload. Everything between the consent screen and
 * Drive is the app's own — `AppModule`, `WindowsRecorder`, `completeRecording`,
 * `ReclyCore.runDueJobs`.
 *
 * It is a test only so that it can be run with one Gradle command and reuse the module's classpath;
 * it is not a unit test and is **skipped** unless `-Drecly.acceptance=1` is given.
 *
 * ```
 * JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :windows:app:test --rerun \
 *   --tests '*DesktopAcceptance*' -Drecly.acceptance=1 \
 *   -Drecly.acceptance.dataDir=/tmp/recly-accept -Drecly.acceptance.authUrlFile=/tmp/recly-auth-url -i
 * ```
 *
 * The consent screen is **not** opened here. The browser seam prints `AUTH_URL=…` and writes it to
 * `recly.acceptance.authUrlFile`; whoever is driving the run opens it, and the loopback receiver
 * (`LoopbackReceiver`, an ephemeral port on 127.0.0.1) catches the redirect. Every step prints one
 * `ACCEPT <step> …` line of evidence, and any failure fails the test — which is the non-zero exit.
 *
 * The Drive files are left in place on purpose: they are the evidence. The processing settings the
 * run saves stay in its own data directory, which is never the user's.
 */
class DesktopAcceptance {

    @Test
    fun `docs20 M6 1 — sign-in → record → Drive`() = runBlocking {
        if (prop(GATE) != "1") {
            println("ACCEPT skipped — no -D$GATE=1")
            return@runBlocking
        }
        val dataDir = File(required(DATA_DIR))
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

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // (3) the plan the recording will run (docs/05): upload only, so no provider key is needed
            val initial = core.initializeProcessing().document
            val settings = ProcessingSettings(transcription = ProcessingTranscription(mode = TranscriptionMode.OFF))
            when (val saved = core.processingSettings.save(settings, initial.revision)) {
                is ProcessingSaveResult.Saved -> Unit
                else -> error("settings: $saved")
            }
            accept("settings", "folder=${settings.storage.folder}", "transcription=${settings.transcription.mode}")

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
            val recordingId = recorder.start()
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

            // (6) what the upload left behind -------------------------------------------------------
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
        } finally {
            scope.cancel()
        }
    }

    // --- plumbing -----------------------------------------------------------------------------------

    private fun accept(step: String, vararg fields: String) {
        println("ACCEPT $step ${fields.joinToString(" ")}")
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

        /** The upload step of the fixed plan (`ProcessingPlan`). */
        const val UPLOAD_STEP = "upload"
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
