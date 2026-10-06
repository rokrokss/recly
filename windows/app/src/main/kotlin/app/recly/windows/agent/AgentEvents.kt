@file:OptIn(ExperimentalTime::class)

package app.recly.windows.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.recly.windows.helper.HelperRestarts
import app.recly.windows.settings.Settings
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import recly.core.platform.Clock
import recly.core.platform.Logger

/**
 * docs/14 "Agent connection": what `recly-events status --json` says (events/, docs/recly.md §15 §9).
 * The app talks to recly-events only by running it — `status --json`, `init`, `serve` — so this is
 * everything it knows about it. The Mac app reads the same JSON (RecKit `AgentEventsStatus`).
 */
@Serializable
data class AgentEventsStatus(
    val home: String,
    val tunnelId: String? = null,
    val tunnelKey: Boolean = false,
    /** Null when no server answers on this home. */
    val server: Server? = null,
    val subscriptions: Int = 0,
    /** There were subscriptions and none is left; recly-events never expires one itself. */
    val subscriptionsEnded: Boolean = false,
) {
    @Serializable
    data class Server(val pid: Long, val tunnelReady: Boolean = false, val tunnelError: String? = null)

    /** Whether an agent is listening, for the running row. */
    val subscription: AgentEventsSubscription
        get() = when {
            subscriptions > 0 -> AgentEventsSubscription.ACTIVE
            subscriptionsEnded -> AgentEventsSubscription.ENDED
            else -> AgentEventsSubscription.NONE
        }

    /**
     * What `serve` needs from its own home: a tunnel and its key. Drive is this app's own connection,
     * handed over on standard input ([AgentEventsCommand.SERVE]).
     */
    val setUp: Boolean get() = !tunnelId.isNullOrEmpty() && tunnelKey

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): AgentEventsStatus? = runCatching { json.decodeFromString<AgentEventsStatus>(text) }.getOrNull()
    }
}

/**
 * Whether an agent listens: one subscribed, none ever did, or one did and stopped — which only the
 * agent can undo, by subscribing again.
 */
enum class AgentEventsSubscription { ACTIVE, NONE, ENDED }

/** What the settings row says. One case per sentence the row can show. */
sealed interface AgentEventsPhase {
    /** This build has no recly-events in it. */
    data object Unavailable : AgentEventsPhase
    /** Recordings go to a local folder, where recly-events sees nothing. */
    data object NotDrive : AgentEventsPhase
    data object Off : AgentEventsPhase
    /** On, but this PC's Google Drive is not connected: the server runs on that connection. */
    data object NeedsDrive : AgentEventsPhase
    /** On, but the tunnel or its key is missing. */
    data object NeedsSetup : AgentEventsPhase
    /** Restarted too often; the user turns it off and on again. */
    data object GaveUp : AgentEventsPhase
    /** A server this app did not start answers on the same home — the CLI, or a terminal. */
    data object Elsewhere : AgentEventsPhase
    data object Starting : AgentEventsPhase
    data object Connecting : AgentEventsPhase
    data object TunnelError : AgentEventsPhase
    data class Running(val subscription: AgentEventsSubscription) : AgentEventsPhase

    companion object {
        fun of(
            enabled: Boolean,
            available: Boolean,
            gaveUp: Boolean,
            owned: Boolean,
            status: AgentEventsStatus?,
            /** Whether recordings go to Google Drive; null until the app has read it. */
            driveStorage: Boolean? = true,
            /** Whether this PC's Drive is connected. */
            driveConnected: Boolean = true,
        ): AgentEventsPhase {
            if (!available) return Unavailable
            if (driveStorage == false) return NotDrive
            if (!enabled) return Off
            if (gaveUp) return GaveUp
            if (driveStorage == null || status == null) return Starting
            if (!driveConnected) return NeedsDrive
            if (!status.setUp) return NeedsSetup
            val server = status.server ?: return Starting
            if (!owned) return Elsewhere
            if (!server.tunnelError.isNullOrEmpty()) return TunnelError
            if (!server.tunnelReady) return Connecting
            return Running(status.subscription)
        }
    }
}

/** What to do about the `serve` this app runs, given what the last `status --json` said. */
enum class AgentEventsAction {
    NONE,
    START,
    STOP,
    ;

    companion object {
        fun reconcile(
            enabled: Boolean,
            gaveUp: Boolean,
            childRunning: Boolean,
            status: AgentEventsStatus?,
        ): AgentEventsAction {
            if (!enabled || gaveUp) return if (childRunning) STOP else NONE
            if (childRunning || status == null || !status.setUp || status.server != null) return NONE
            return START
        }
    }
}

/** The commands the app runs, in one place. */
object AgentEventsCommand {
    val STATUS = listOf("status", "--json")
    /**
     * Takes this app's Drive access token on standard input, one per line, in place of a Google
     * sign-in of its own, and stops when the app's end closes — when the app goes, however it goes.
     * `ProcessBuilder` gives the child a pipe there by default, and the app never closes its end.
     */
    val SERVE = listOf("serve", "--drive-token-stdin")

    /** The key goes in on standard input, never in the arguments, which other processes can read. */
    fun saveTunnel(id: String, hasKey: Boolean): List<String> =
        listOf("init", "--tunnel-id", id) + (if (hasKey) listOf("--tunnel-key-stdin") else emptyList()) + "--no-check"
}

/**
 * Where the bundled recly-events is: `recly-events.exe` next to the capture helper in the MSI's
 * resources (docs/14 "App"), or [OVERRIDE_ENV] — the development host has no MSI, and points it at
 * `events/bin/recly-events`.
 */
object AgentEventsProgram {
    const val OVERRIDE_ENV = "RECLY_EVENTS"
    private const val RESOURCES_PROPERTY = "compose.application.resources.dir"
    private const val BINARY = "recly-events.exe"

    fun locate(
        env: (String) -> String? = System::getenv,
        property: (String) -> String? = System::getProperty,
        exists: (String) -> Boolean = { File(it).canExecute() },
    ): String? {
        env(OVERRIDE_ENV)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val resources = property(RESOURCES_PROPERTY) ?: return null
        val binary = "$resources${File.separator}$BINARY"
        return binary.takeIf(exists)
    }
}

/** Runs the program: one command to its end, or `serve` until it is stopped. */
interface AgentEventsRunner {
    /** Exit code and standard output; -1 when it could not be started or was stopped at [timeout]. */
    suspend fun run(arguments: List<String>, input: String? = null, timeout: Duration? = null): Pair<Int, String>

    fun serve(log: File): Process
}

class ProcessRunner(private val program: String) : AgentEventsRunner {
    override suspend fun run(arguments: List<String>, input: String?, timeout: Duration?): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val process = runCatching {
                ProcessBuilder(listOf(program) + arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            }.getOrElse { return@withContext -1 to "" }
            process.outputStream.use { stdin -> input?.let { stdin.write(it.toByteArray()) } }
            // Read while waiting, so a stop at the timeout also ends the read.
            val output = async { process.inputStream.bufferedReader().use { it.readText() } }
            val finished = if (timeout == null) {
                process.waitFor()
                true
            } else {
                process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }
            if (!finished) process.destroy()
            (if (finished) process.exitValue() else -1) to output.await()
        }

    override fun serve(log: File): Process {
        log.parentFile?.mkdirs()
        return ProcessBuilder(program, *AgentEventsCommand.SERVE.toTypedArray())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
    }
}

/**
 * docs/14 "Agent connection": keeps `recly-events serve` running while the switch is on, on this PC's
 * own Drive connection, and runs `init` for the tunnel row. Polls `status --json` every [poll] while
 * the switch is on. Null [runner] is a build without recly-events, which the switch says.
 */
class AgentEvents(
    private val settings: Settings,
    private val runner: AgentEventsRunner?,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val logger: Logger? = null,
    private val poll: Duration = 5.seconds,
    /** Whether this PC's Drive is connected, as the app's own Drive row says; the server runs on it. */
    private val driveConnected: () -> Boolean = { true },
    /**
     * This PC's current Drive access token, null when there is none to give right now. It goes to the
     * server on its standard input and nowhere else (docs/recly.md §15 §9).
     */
    private val driveToken: suspend () -> String? = { null },
) {
    var phase by mutableStateOf<AgentEventsPhase>(if (runner == null) AgentEventsPhase.Unavailable else AgentEventsPhase.Off)
        private set
    var enabled by mutableStateOf(settings.agentEvents)
        private set

    /** The saved tunnel ID, for the field's first value. */
    var tunnelId by mutableStateOf("")
        private set
    /** A tunnel key is saved; the key itself is never read back. */
    var tunnelKeySaved by mutableStateOf(false)
        private set

    /** The last tunnel save failed (a bad ID, no key). */
    var saveFailed by mutableStateOf(false)
        private set

    private val lock = Mutex()
    private var child: Process? = null
    /** The token last written to the server's standard input, so a token is written once. */
    private var givenToken: String? = null
    private var gaveUp = false
    private var restarts = HelperRestarts()
    private var status: AgentEventsStatus? = null
    private var poller: Job? = null
    /** Whether recordings go to Google Drive, the only storage recly-events can watch; null until told. */
    @Volatile private var driveStorage: Boolean? = null

    /**
     * Off means the program is not run at all: the poller only runs it while the switch is on and
     * recordings go to Google Drive.
     */
    fun start() {
        poller = scope.launch {
            while (isActive) {
                if ((enabled && driveStorage == true && driveConnected()) || child != null) refresh()
                delay(poll)
            }
        }
    }

    /** docs/03 "Storage location": where recordings go now. Nothing is started before the first answer. */
    fun storageChanged(drive: Boolean) {
        if (driveStorage == drive) return
        driveStorage = drive
        log(Logger.Level.INFO, "agent.storage", mapOf("drive" to drive))
        scope.launch { refresh() }
    }

    /** The section came on screen: look now rather than at the next poll. */
    fun sectionShown() {
        scope.launch { refresh() }
    }

    fun toggle(value: Boolean) {
        if (value == enabled) return
        enabled = value
        settings.agentEvents = value
        gaveUp = false
        restarts = HelperRestarts()
        log(Logger.Level.INFO, "agent.enabled", mapOf("value" to value))
        scope.launch { refresh() }
    }

    /**
     * `init --tunnel-id`, with the key on standard input when one was typed — without one, the saved
     * key stays. [onSaved] runs once the program has taken it.
     */
    fun saveTunnel(id: String, key: String, onSaved: () -> Unit = {}) {
        val runner = runner ?: return
        val trimmedKey = key.trim()
        scope.launch {
            val (code, _) = runner.run(
                AgentEventsCommand.saveTunnel(id.trim(), hasKey = trimmedKey.isNotEmpty()),
                input = trimmedKey.takeIf { it.isNotEmpty() },
            )
            saveFailed = code != 0
            log(Logger.Level.INFO, "agent.tunnel.save", mapOf("exit" to code))
            if (code == 0) {
                gaveUp = false
                restarts = HelperRestarts()
                onSaved()
                restartChild()
            }
            refresh()
        }
    }

    /** The app is quitting: the server goes with it. */
    fun shutdown() {
        poller?.cancel()
        stopChild()
    }

    suspend fun refresh() = lock.withLock {
        val runner = runner ?: return@withLock publish()
        child?.let { process ->
            if (!process.isAlive) {
                child = null
                log(Logger.Level.INFO, "agent.serve.exit", mapOf("code" to process.exitValue()))
                if (enabled && !restarts.allow(clock.now())) {
                    gaveUp = true
                    log(Logger.Level.ERROR, "agent.serve.gaveUp")
                }
            }
        }
        val (code, output) = runner.run(AgentEventsCommand.STATUS)
        val current = if (code == 0) AgentEventsStatus.parse(output) else null
        status = current
        if (current != null) {
            tunnelId = current.tunnelId.orEmpty()
            tunnelKeySaved = current.tunnelKey
        }
        when (AgentEventsAction.reconcile(enabled && driveStorage == true && driveConnected(), gaveUp, child != null, current)) {
            AgentEventsAction.START -> current?.let { startChild(runner, it.home) }
            AgentEventsAction.STOP -> stopChild()
            AgentEventsAction.NONE -> Unit
        }
        giveToken()
        publish()
    }

    private fun publish() {
        val owned = child != null && status?.server?.pid == child?.pid()
        phase = AgentEventsPhase.of(enabled, runner != null, gaveUp, owned, status, driveStorage, driveConnected())
    }

    /**
     * The server's Drive access is this PC's: its current token, written when it is new — at the
     * start, and after each refresh the app makes. Never logged; a server that has just died fails
     * the write, which the next poll notices.
     */
    private suspend fun giveToken() {
        val process = child ?: return
        val token = driveToken() ?: return
        if (token == givenToken) return
        try {
            withContext(Dispatchers.IO) {
                process.outputStream.write("$token\n".toByteArray())
                process.outputStream.flush()
            }
            givenToken = token
        } catch (e: java.io.IOException) {
            log(Logger.Level.ERROR, "agent.token.failed")
        }
    }

    private fun startChild(runner: AgentEventsRunner, home: String) {
        // The service's own log file (events/README.md "Everyday use"), so one place has it all.
        givenToken = null
        child = runCatching { runner.serve(File(File(home, "logs"), "serve.log")) }
            .onSuccess { log(Logger.Level.INFO, "agent.serve.start") }
            .onFailure {
                log(Logger.Level.ERROR, "agent.serve.failed", error = it)
                gaveUp = !restarts.allow(clock.now())
            }
            .getOrNull()
    }

    private fun stopChild() {
        val process = child ?: return
        child = null
        // A polite stop first: `serve` closes its tunnel and its socket on the way out.
        process.destroy()
        log(Logger.Level.INFO, "agent.serve.stop")
    }

    /** Stops the server and waits for it to go, so the next refresh starts a new one. */
    private suspend fun restartChild() {
        val process = lock.withLock { child?.also { stopChild() } } ?: return
        withContext(Dispatchers.IO) { process.waitFor(10, TimeUnit.SECONDS) }
    }

    private fun log(level: Logger.Level, event: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null) {
        logger?.log(level, event, fields, error)
    }
}
