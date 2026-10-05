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

    private val ready = AgentEventsStatus(home = "/h", tunnelId = "tunnel_x", tunnelKey = true, googleSignedIn = true)

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
    fun `google ends when Google refuses the sign-in`() {
        assertTrue(ready.copy(drive = AgentEventsStatus.Drive("""oauth2: "invalid_grant" "Token has been expired or revoked."""")).googleEnded)
        assertFalse(ready.copy(drive = AgentEventsStatus.Drive("drive: HTTP 503: backend")).googleEnded)
    }

    @Test
    fun `the phase says the most urgent thing first`() {
        fun phase(enabled: Boolean = true, available: Boolean = true, signingIn: Boolean = false, gaveUp: Boolean = false, owned: Boolean = true, status: AgentEventsStatus?) =
            AgentEventsPhase.of(enabled, available, signingIn, gaveUp, owned, status)
        val running = ready.copy(server = AgentEventsStatus.Server(pid = 7, tunnelReady = true))
        assertEquals(AgentEventsPhase.Unavailable, phase(available = false, status = running))
        assertEquals(AgentEventsPhase.Off, phase(enabled = false, status = running))
        assertEquals(AgentEventsPhase.SigningIn, phase(signingIn = true, status = running))
        assertEquals(AgentEventsPhase.GaveUp, phase(gaveUp = true, status = running))
        assertEquals(AgentEventsPhase.Starting, phase(status = null))
        assertEquals(AgentEventsPhase.NeedsSetup, phase(status = AgentEventsStatus(home = "/h")))
        assertEquals(AgentEventsPhase.Starting, phase(status = ready))
        assertEquals(AgentEventsPhase.Elsewhere, phase(owned = false, status = running))
        assertEquals(AgentEventsPhase.Running(AgentEventsSubscription.NONE), phase(status = running))
        assertEquals(AgentEventsPhase.Running(AgentEventsSubscription.ACTIVE), phase(status = running.copy(subscriptions = 1)))
        assertEquals(AgentEventsPhase.Running(AgentEventsSubscription.ENDED), phase(status = running.copy(subscriptionsEnded = true)))
        val connecting = running.copy(server = AgentEventsStatus.Server(pid = 7, tunnelReady = false))
        assertEquals(AgentEventsPhase.Connecting, phase(status = connecting))
        assertEquals(AgentEventsPhase.TunnelError, phase(status = connecting.copy(server = connecting.server?.copy(tunnelError = "tunnel: unauthorized"))))
        assertEquals(AgentEventsPhase.GoogleEnded, phase(status = running.copy(drive = AgentEventsStatus.Drive("invalid_grant"))))
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
        agent.saveTunnel(" tunnel_y ", " sk-test ")
        runner.awaitCall { it.firstOrNull() == "init" }
        assertEquals(listOf("init", "--tunnel-id", "tunnel_y", "--tunnel-key-stdin", "--no-check"), runner.calls.first { it.first() == "init" })
        assertEquals("sk-test", runner.stdin)
    }

    private fun withAgent(diesAtOnce: Boolean = false, block: suspend (AgentEvents, FakeRunner, FakeSettings) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = FakeRunner(diesAtOnce)
        val settings = FakeSettings()
        val agent = AgentEvents(settings, runner, scope, FixedClock(), poll = 1.hours)
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

        override suspend fun run(arguments: List<String>, input: String?, timeout: Duration?): Pair<Int, String> {
            calls += arguments
            input?.let { stdin = it }
            if (arguments != AgentEventsCommand.STATUS) return 0 to ""
            val server = serving?.takeIf { it.isAlive }?.let { """{"pid": ${it.pid()}, "tunnelReady": true}""" } ?: "null"
            return 0 to """{"home":"/h","tunnelId":"tunnel_x","tunnelKey":true,"googleSignedIn":true,"server":$server,"drive":{},"subscriptions":0}"""
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
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
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
