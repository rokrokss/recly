package app.recly.windows.helper

import app.recly.windows.i18n.Str
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import java.io.BufferedWriter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import recly.core.platform.Logger

/**
 * The app half of the docs/14 helper protocol: spawns the capture helper, writes commands to its
 * stdin and turns its stdout into [events].
 *
 * **The channel closing is the helper's death.** There is no "the helper exited" event, because a
 * helper that is killed does not get to send one — so the one signal the app acts on is the one it
 * cannot miss: stdout reaching EOF. docs/14 "헬퍼가 죽으면 앱이 마지막 파트까지를 finalize한다" is
 * therefore implemented by whoever is consuming [events] running off the end of the loop.
 */
class HelperClient(
    private val command: List<String>,
    private val io: CoroutineDispatcher,
    private val logger: Logger,
) {
    // Unlimited, never conflating: a `part_done` is a segment of the user's recording, and a
    // consumer that is a moment behind (an `addPart` writing to disk) must not cost one.
    private val channel = Channel<HelperEvent>(Channel.UNLIMITED)
    private var process: Process? = null
    private var stdin: BufferedWriter? = null

    /** Closed when the helper's stdout ends — see the class comment. */
    val events: ReceiveChannel<HelperEvent> get() = channel

    /**
     * Starts the process and the reader. [scope] outlives one recording: the reader keeps going
     * until the helper's stdout ends, which is after the last `part_done` of a stop.
     */
    suspend fun open(scope: CoroutineScope) {
        val started = withContext(io) {
            ProcessBuilder(command).redirectErrorStream(false).start()
        }
        process = started
        stdin = started.outputStream.bufferedWriter()
        logger.log(Logger.Level.INFO, "helper.spawn", mapOf("command" to command.first()))
        scope.launch(io) { read(started) }
        scope.launch(io) { drainStderr(started) }
    }

    suspend fun send(command: HelperCommand) {
        val line = helperJson.encodeToString(HelperCommand.serializer(), command)
        withContext(io) {
            val writer = stdin ?: return@withContext
            // A helper that has already died closes the pipe; the death is handled by the reader
            // and there is nothing this write could add to it.
            runCatching {
                writer.write(line)
                writer.newLine()
                writer.flush()
            }.onFailure { logger.log(Logger.Level.WARN, "helper.send.failed", mapOf("command" to line), it) }
        }
    }

    /** Closes stdin (the helper's own cue to exit), then gives it [graceMs] before killing it. */
    suspend fun close(graceMs: Long = GRACE_MS) = withContext(io) {
        runCatching { stdin?.close() }
        val running = process ?: return@withContext
        if (!running.waitFor(graceMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            logger.log(Logger.Level.WARN, "helper.kill")
            running.destroyForcibly()
        }
        Unit
    }

    private fun read(process: Process) {
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val event = runCatching {
                    helperJson.decodeFromString(HelperEvent.serializer(), line)
                }.getOrElse {
                    // Not a protocol line — a panic message, a stray print. Logged and skipped: a
                    // recording must not end because the helper wrote something unexpected.
                    logger.log(Logger.Level.WARN, "helper.unparsed", mapOf("line" to line.take(200)))
                    return@forEachLine
                }
                val sent = channel.trySend(event)
                if (sent.isFailure) {
                    // Closed means nobody is listening any more — the recording is over and the
                    // helper is on its way out. Anything else cannot happen to an unlimited
                    // channel, and would be a lost part if it did.
                    if (sent.isClosed) return@forEachLine
                    logger.log(
                        Logger.Level.ERROR,
                        "helper.event.dropped",
                        mapOf("line" to line.take(200)),
                    )
                }
            }
        } catch (e: Exception) {
            logger.log(Logger.Level.WARN, "helper.read.failed", error = e)
        } finally {
            // The channel first, and only then the exit code: whoever is consuming the events is
            // waiting on the close, and the process may still be on its way out — stdout ending is
            // not the same moment as the process ending, and `exitValue` on a live one throws.
            channel.close()
            val exit = runCatching {
                if (process.waitFor(EXIT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.exitValue()
                } else {
                    null
                }
            }.getOrNull()
            logger.log(Logger.Level.INFO, "helper.ended", mapOf("exit" to exit))
        }
    }

    /** stderr is the helper's log, not its protocol — it goes to ours (docs/20 shared stream). */
    private fun drainStderr(process: Process) {
        runCatching {
            process.errorStream.bufferedReader().forEachLine {
                logger.log(Logger.Level.INFO, "helper.stderr", mapOf("line" to it.take(200)))
            }
        }
    }

    private companion object {
        const val GRACE_MS = 5_000L

        /** How long the reader waits for the process it has just seen close its stdout. */
        const val EXIT_WAIT_MS = 1_000L
    }
}

/**
 * Where the capture helper is, if it is anywhere. docs/14 packages it as a resource of the MSI;
 * this development host has no Rust helper at all (M6-L2), so the env var is the way a fake one is
 * put in front of the app — see `windows/app/README.md`.
 *
 * Null is a first-class answer: the tray says [Str.STATUS_HELPER_MISSING] and recording is disabled
 * rather than offered and failing (deliverable 5).
 */
object CaptureHelper {
    const val OVERRIDE_ENV = "RECLY_CAPTURE_HELPER"

    /** jpackage puts `appResourcesRootDir` here, and Compose Desktop passes it to the app. */
    private const val RESOURCES_PROPERTY = "compose.application.resources.dir"

    fun command(
        env: (String) -> String? = System::getenv,
        property: (String) -> String? = System::getProperty,
        exists: (String) -> Boolean = { java.io.File(it).canExecute() },
    ): List<String>? {
        // A command line, not a path: the dev fake is `java …/FakeHelper.java`, and a helper that
        // needs an argument on a user's machine should not need a new release to get one.
        env(OVERRIDE_ENV)?.takeIf { it.isNotBlank() }?.let { return it.trim().split(" ") }
        val resources = property(RESOURCES_PROPERTY) ?: return null
        val binary = "$resources${java.io.File.separator}$BINARY"
        if (!exists(binary)) return null
        // ADR-019: the MSI carries an LGPL ffmpeg next to the helper, and nothing puts it on PATH —
        // which is where the helper would otherwise look for it (`--ffmpeg` defaults to "ffmpeg").
        val ffmpeg = "$resources${java.io.File.separator}$FFMPEG"
        return if (exists(ffmpeg)) listOf(binary, "--ffmpeg", ffmpeg) else listOf(binary)
    }

    /**
     * The bundled ffmpeg (ADR-019), which is also what `audio.concat` remuxes a recording's parts
     * with (docs/08). Falls back to the bare name — a development host has nothing bundled and
     * does have ffmpeg on `PATH`.
     */
    fun ffmpeg(
        property: (String) -> String? = System::getProperty,
        exists: (String) -> Boolean = { java.io.File(it).canExecute() },
    ): String {
        val resources = property(RESOURCES_PROPERTY) ?: return FFMPEG
        val bundled = "$resources${java.io.File.separator}$FFMPEG"
        return if (exists(bundled)) bundled else FFMPEG
    }

    /**
     * docs/lanes/M6-L3 deliverable 3, the "버전 확인" half: what the binary at the end of [command]
     * says it is. Null when it could not be run at all, which is the answer that matters — the path
     * check above only says a file is there.
     *
     * stdin is closed straight away so that a helper which does not know `--version` (the
     * development host's `FakeHelper.java`, which waits for commands) ends instead of hanging.
     */
    fun version(command: List<String>, timeoutMs: Long = VERSION_TIMEOUT_MS): String? = runCatching {
        val process = ProcessBuilder(command + "--version")
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return null
        }
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    }.getOrNull()

    /**
     * `--self-test` (M6-L2): the helper's own report on the machine it is installed on — endpoints,
     * the AAC encoder, ffmpeg. Exposed in the settings window because it is the first thing a
     * support question needs and the user cannot run it from a console the MSI does not give them.
     *
     * The report itself is the helper's own text and is shown as it stands; only what this app has
     * to say about a helper that did not answer is translated.
     */
    fun selfTest(command: List<String>, timeoutMs: Long = SELF_TEST_TIMEOUT_MS): UiMessage =
        runCatching {
            val process = ProcessBuilder(command + "--self-test").redirectErrorStream(true).start()
            process.outputStream.close()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return Str.SELF_TEST_NO_ANSWER.message()
            }
            if (text.isBlank()) Str.SELF_TEST_EMPTY.message() else UiMessage.Text(text)
        }.getOrElse { Str.SELF_TEST_FAILED.message(it.message.orEmpty()) }

    private const val VERSION_TIMEOUT_MS = 2_000L

    private const val SELF_TEST_TIMEOUT_MS = 30_000L

    private val BINARY: String =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            "recly-capture-helper.exe"
        } else {
            "recly-capture-helper"
        }

    private val FFMPEG: String =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            "ffmpeg.exe"
        } else {
            "ffmpeg"
        }
}
