@file:OptIn(ExperimentalTime::class)

package app.recly.windows.agent

import app.recly.windows.FakeSettings
import app.recly.windows.FixedClock
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * docs/14 "Agent connection": what the Windows app makes of `recly-events status --json`, when it
 * starts and stops `serve`, and how it gives up on one that keeps dying. The Mac's RecKit
 * `AgentEventsTests` say the same about the same JSON.
 */
class AgentEventsTest {

    private val ready = AgentEventsStatus(home = "/h", tunnelId = "tunnel_x", tunnelKey = true)

    @Test
    fun `reads what status --json prints`() {
        val status = assertNotNull(AgentEventsStatus.parse("""
            {"home": "/h", "tunnelId": "tunnel_x", "tunnelKey": true, "googleSignedIn": true,
             "server": {"pid": 42, "tunnelReady": true, "startedAt": "2026-10-05T12:39:28+04:00", "version": "0.1.0"},
             "drive": {"pollSeconds": 10, "announced": 2}, "subscriptions": 1, "pending": 0,
             "lastDelivery": {"at": "2026-10-05T12:10:48+04:00", "status": 200}}
        """.trimIndent()))
        assertEquals(42L, status.server?.pid)
        assertTrue(status.setUp)
        assertEquals(1, status.subscriptions)
        val fresh = assertNotNull(AgentEventsStatus.parse("""{"home": "/h", "tunnelKey": false, "googleSignedIn": false, "server": null, "drive": {}, "subscriptions": 0}"""))
        assertNull(fresh.server)
        assertFalse(fresh.setUp)
        assertNull(AgentEventsStatus.parse("recly-events: usage"))
    }

    @Test
    fun `set-up is the tunnel alone, since Drive is the app's own`() {
        assertTrue(ready.setUp, "no Google sign-in of its own is needed")
        assertFalse(AgentEventsStatus(home = "/h", tunnelId = "tunnel_x").setUp)
        assertFalse(AgentEventsStatus(home = "/h", tunnelKey = true).setUp)
    }

    @Test
    fun `the phase says the most urgent thing first`() {
        fun phase(enabled: Boolean = true, available: Boolean = true, gaveUp: Boolean = false, owned: Boolean = true, drive: Boolean = true, status: AgentEventsStatus?) =
            AgentEventsPhase.of(enabled, available, gaveUp, owned, status, driveConnected = drive)
        val running = ready.copy(server = AgentEventsStatus.Server(pid = 7, tunnelReady = true))
        assertEquals(AgentEventsPhase.Unavailable, phase(available = false, status = running))
        assertEquals(AgentEventsPhase.Off, phase(enabled = false, status = running))
        assertEquals(AgentEventsPhase.GaveUp, phase(gaveUp = true, status = running))
        assertEquals(AgentEventsPhase.Starting, phase(status = null))
        assertEquals(AgentEventsPhase.NeedsSetup, phase(status = AgentEventsStatus(home = "/h")))
        assertEquals(AgentEventsPhase.Starting, phase(status = ready))
        assertEquals(AgentEventsPhase.Elsewhere, phase(owned = false, status = running))
        assertEquals(AgentEventsPhase.NeedsDrive, phase(drive = false, status = ready))
        assertEquals(AgentEventsPhase.NeedsDrive, phase(drive = false, status = AgentEventsStatus(home = "/h")), "said before the tunnel the row asks for")
        assertEquals(AgentEventsPhase.Running(AgentEventsSubscription.NONE), phase(status = running))
        assertEquals(AgentEventsPhase.Running(AgentEventsSubscription.ACTIVE), phase(status = running.copy(subscriptions = 1)))
        assertEquals(AgentEventsPhase.Running(AgentEventsSubscription.ENDED), phase(status = running.copy(subscriptionsEnded = true)))
        val connecting = running.copy(server = AgentEventsStatus.Server(pid = 7, tunnelReady = false))
        assertEquals(AgentEventsPhase.Connecting, phase(status = connecting))
        assertEquals(AgentEventsPhase.TunnelError, phase(status = connecting.copy(server = connecting.server?.copy(tunnelError = "tunnel: unauthorized"))))
    }

    @Test
    fun `a storage recly-events cannot watch says so before anything else, and an unread one waits`() {
        val running = ready.copy(server = AgentEventsStatus.Server(pid = 7, tunnelReady = true))
        assertEquals(AgentEventsPhase.Unavailable, AgentEventsPhase.of(true, false, false, true, running, driveStorage = false))
        assertEquals(AgentEventsPhase.NotDrive, AgentEventsPhase.of(true, true, false, true, running, driveStorage = false))
        assertEquals(AgentEventsPhase.NotDrive, AgentEventsPhase.of(false, true, false, true, running, driveStorage = false), "said even while off")
        assertEquals(AgentEventsPhase.Starting, AgentEventsPhase.of(true, true, false, true, running, driveStorage = null))
        assertEquals(AgentEventsPhase.Off, AgentEventsPhase.of(false, true, false, true, running, driveStorage = null))
    }

    @Test
    fun `serve starts only when on, set up and nothing else answers`() {
        fun act(enabled: Boolean = true, gaveUp: Boolean = false, child: Boolean = false, status: AgentEventsStatus?) =
            AgentEventsAction.reconcile(enabled, gaveUp = gaveUp, childRunning = child, status = status)
        assertEquals(AgentEventsAction.START, act(status = ready))
        assertEquals(AgentEventsAction.NONE, act(status = null))
        assertEquals(AgentEventsAction.NONE, act(status = AgentEventsStatus(home = "/h")))
        assertEquals(AgentEventsAction.NONE, act(status = ready.copy(server = AgentEventsStatus.Server(pid = 9, tunnelReady = true))))
        assertEquals(AgentEventsAction.NONE, act(child = true, status = ready))
        assertEquals(AgentEventsAction.STOP, act(enabled = false, child = true, status = ready))
        assertEquals(AgentEventsAction.STOP, act(gaveUp = true, child = true, status = ready))
        assertEquals(AgentEventsAction.NONE, act(enabled = false, status = ready))
    }

    @Test
    fun `the key never goes in the arguments`() {
        assertEquals(listOf("init", "--tunnel-id", "tunnel_x", "--tunnel-key-stdin", "--no-check"), AgentEventsCommand.saveTunnel("tunnel_x", hasKey = true))
        assertEquals(listOf("init", "--tunnel-id", "tunnel_x", "--no-check"), AgentEventsCommand.saveTunnel("tunnel_x", hasKey = false))
    }

    @Test
    fun `the program is the override, else the one in the MSI's resources`() {
        assertEquals("/dev/recly-events", AgentEventsProgram.locate(env = { "/dev/recly-events" }, property = { null }, exists = { false }))
        val bundled = "C:\\Recly\\app${File.separator}recly-events.exe"
        assertEquals(bundled, AgentEventsProgram.locate(env = { null }, property = { "C:\\Recly\\app" }, exists = { it == bundled }))
        assertNull(AgentEventsProgram.locate(env = { null }, property = { "C:\\Recly\\app" }, exists = { false }))
        assertNull(AgentEventsProgram.locate(env = { null }, property = { null }, exists = { true }))
    }

    @Test
    fun `serve runs while the switch is on and stops when it goes off`() = withAgent { agent, runner, settings ->
        agent.refresh()
        assertEquals(AgentEventsPhase.Off, agent.phase)
        assertNull(runner.serving)

        agent.toggle(true)
        assertTrue(settings.agentEvents)
        agent.refresh()
        val server = assertNotNull(runner.serving)
        agent.refresh()
        assertEquals(AgentEventsPhase.Running(AgentEventsSubscription.NONE), agent.phase)

        agent.toggle(false)
        agent.refresh()
        assertFalse(server.isAlive, "serve was stopped")
        assertEquals(AgentEventsPhase.Off, agent.phase)
        assertFalse(settings.agentEvents)
    }

    @Test
    fun `a server that keeps dying is reported, not respawned for ever`() = withAgent(diesAtOnce = true) { agent, runner, _ ->
        agent.toggle(true)
        repeat(6) { agent.refresh() }
        assertEquals(AgentEventsPhase.GaveUp, agent.phase)
        assertEquals(4, runner.started, "the first start and three restarts")
    }

    @Test
    fun `a saved tunnel passes the key on standard input`() = withAgent { agent, runner, _ ->
        val saved = java.util.concurrent.atomic.AtomicBoolean(false)
        agent.saveTunnel(" tunnel_y ", " sk-test ") { saved.set(true) }
        runner.awaitCall { it.firstOrNull() == "init" }
        assertEquals(listOf("init", "--tunnel-id", "tunnel_y", "--tunnel-key-stdin", "--no-check"), runner.calls.first { it.first() == "init" })
        assertEquals("sk-test", runner.stdin)
        repeat(100) { if (!saved.get()) kotlinx.coroutines.delay(20) }
        assertTrue(saved.get(), "the row closes its fields once the program took the tunnel")
    }

    @Test
    fun `a tunnel saved with the switch off starts nothing`() = withAgent { agent, runner, _ ->
        agent.refresh()
        val saved = java.util.concurrent.atomic.AtomicBoolean(false)
        agent.saveTunnel("tunnel_y", "sk-test") { saved.set(true) }
        repeat(100) { if (!saved.get()) kotlinx.coroutines.delay(20) }
        assertTrue(saved.get())
        agent.refresh()
        assertNull(runner.serving, "the switch alone decides whether it runs")
        assertEquals(AgentEventsPhase.Off, agent.phase)
    }

    @Test
    fun `nothing runs until the storage is known, and a storage that is not Drive stops the server`() = withAgent(storage = null) { agent, runner, _ ->
        agent.toggle(true)
        agent.refresh()
        assertNull(runner.serving, "not started before the storage was read")
        assertEquals(AgentEventsPhase.Starting, agent.phase)

        agent.storageChanged(true)
        agent.refresh()
        val server = assertNotNull(runner.serving)
        agent.storageChanged(false)
        agent.refresh()
        assertFalse(server.isAlive, "a local folder is nothing recly-events can watch")
        assertEquals(AgentEventsPhase.NotDrive, agent.phase)
    }

    @Test
    fun `the server gets this PC's Drive token on standard input, each new one once`() = withAgent { agent, runner, _ ->
        agent.toggle(true)
        agent.refresh()
        val server = assertNotNull(runner.serving)
        assertEquals("tok-1\n", server.written())
        agent.refresh()
        assertEquals("tok-1\n", server.written(), "a token is written once")
        runner.token = "tok-2"
        agent.refresh()
        assertEquals("tok-1\ntok-2\n", server.written())
        runner.token = null
        agent.refresh()
        assertEquals("tok-1\ntok-2\n", server.written(), "nothing to give, nothing written")
    }

    @Test
    fun `without this PC's Drive the server is not run, and stops when Drive goes`() = withAgent { agent, runner, _ ->
        runner.connected = false
        agent.toggle(true)
        agent.refresh()
        assertNull(runner.serving)
        assertEquals(AgentEventsPhase.NeedsDrive, agent.phase)
        runner.connected = true
        agent.refresh()
        val server = assertNotNull(runner.serving)
        runner.connected = false
        agent.refresh()
        assertFalse(server.isAlive, "the server runs on this PC's Drive")
        agent.refresh()
        assertEquals(AgentEventsPhase.NeedsDrive, agent.phase)
    }

    private fun withAgent(
        diesAtOnce: Boolean = false,
        storage: Boolean? = true,
        block: suspend (AgentEvents, FakeRunner, FakeSettings) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = FakeRunner(diesAtOnce)
        val settings = FakeSettings()
        val agent = AgentEvents(
            settings, runner, scope, FixedClock(), poll = 1.hours,
            driveConnected = { runner.connected },
            driveToken = { runner.token },
        )
        storage?.let { agent.storageChanged(it) }
        try {
            block(agent, runner, settings)
        } finally {
            agent.shutdown()
            scope.cancel()
        }
    }

    /** `status --json` as the program answers it, and a `serve` that is a process in name only. */
    private class FakeRunner(private val diesAtOnce: Boolean) : AgentEventsRunner {
        val calls = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        @Volatile var stdin: String? = null
        @Volatile var serving: FakeProcess? = null
        @Volatile var started = 0
        /** This PC's Drive as the app would give it: connected, and its current access token. */
        @Volatile var connected = true
        @Volatile var token: String? = "tok-1"

        override suspend fun run(arguments: List<String>, input: String?, timeout: Duration?): Pair<Int, String> {
            calls += arguments
            input?.let { stdin = it }
            if (arguments != AgentEventsCommand.STATUS) return 0 to ""
            val server = serving?.takeIf { it.isAlive }?.let { """{"pid": ${it.pid()}, "tunnelReady": true}""" } ?: "null"
            return 0 to """{"home":"/h","tunnelId":"tunnel_x","tunnelKey":true,"server":$server,"subscriptions":0}"""
        }

        override fun serve(log: File): Process {
            started++
            return FakeProcess(4242L + started, alive = !diesAtOnce).also { serving = it }
        }

        suspend fun awaitCall(match: (List<String>) -> Boolean) {
            repeat(100) {
                if (calls.any(match)) return
                kotlinx.coroutines.delay(20)
            }
        }
    }

    private class FakeProcess(private val id: Long, @Volatile private var alive: Boolean) : Process() {
        /** What the app wrote to the server's standard input. */
        private val stdin = java.io.ByteArrayOutputStream()
        fun written(): String = synchronized(stdin) { stdin.toString(Charsets.UTF_8) }
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = synchronized(stdin) { stdin.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) = synchronized(stdin) { stdin.write(b, off, len) }
        }
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 1
        override fun destroy() {
            alive = false
        }
        override fun isAlive(): Boolean = alive
        override fun pid(): Long = id
    }
}
