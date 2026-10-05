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
        assertNull(status.googleAccountId)
        val fresh = assertNotNull(AgentEventsStatus.parse("""{"home": "/h", "tunnelKey": false, "googleSignedIn": false, "server": null, "drive": {}, "subscriptions": 0}"""))
        assertNull(fresh.server)
        assertFalse(fresh.setUp)
        assertEquals("perm-1", AgentEventsStatus.parse("""{"home": "/h", "googleSignedIn": true, "googleAccountId": "perm-1"}""")?.googleAccountId)
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
    fun `a storage recly-events cannot watch says so before anything else, and an unread one waits`() {
        val running = ready.copy(server = AgentEventsStatus.Server(pid = 7, tunnelReady = true))
        assertEquals(AgentEventsPhase.Unavailable, AgentEventsPhase.of(true, false, false, false, true, running, driveStorage = false))
        assertEquals(AgentEventsPhase.NotDrive, AgentEventsPhase.of(true, true, false, false, true, running, driveStorage = false))
        assertEquals(AgentEventsPhase.NotDrive, AgentEventsPhase.of(false, true, false, false, true, running, driveStorage = false), "said even while off")
        assertEquals(AgentEventsPhase.Starting, AgentEventsPhase.of(true, true, false, false, true, running, driveStorage = null))
        assertEquals(AgentEventsPhase.Off, AgentEventsPhase.of(false, true, false, false, true, running, driveStorage = null))
    }

    @Test
    fun `the account is the same, another, or not known from one side`() {
        assertEquals(AgentEventsAccount.SAME, AgentEventsAccount.of("perm-1", "perm-1"))
        assertEquals(AgentEventsAccount.DIFFERENT, AgentEventsAccount.of("perm-1", "perm-2"))
        assertEquals(AgentEventsAccount.UNKNOWN, AgentEventsAccount.of(null, "perm-1"))
        assertEquals(AgentEventsAccount.UNKNOWN, AgentEventsAccount.of("perm-1", null))
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
    fun `a tunnel saved or a sign-in made with the switch off starts nothing`() = withAgent { agent, runner, _ ->
        agent.refresh()
        val saved = java.util.concurrent.atomic.AtomicBoolean(false)
        agent.saveTunnel("tunnel_y", "sk-test") { saved.set(true) }
        repeat(100) { if (!saved.get()) kotlinx.coroutines.delay(20) }
        assertTrue(saved.get())
        agent.connectGoogle()
        runner.awaitCall { it == AgentEventsCommand.SIGN_IN }
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
    fun `the sign-in is compared with the upload account once per account and showing`() = withAgent(account = "perm-1", upload = { "perm-1" }) { agent, runner, _ ->
        agent.toggle(true)
        agent.refresh()
        assertEquals(AgentEventsAccount.SAME, agent.account)
        agent.refresh()
        assertEquals(1, runner.uploadAsks, "not asked again on every poll")

        runner.account = "perm-2"
        agent.refresh()
        assertEquals(AgentEventsAccount.DIFFERENT, agent.account)
        assertEquals(2, runner.uploadAsks, "a new sign-in account is asked about")
    }

    @Test
    fun `no account is asked about while the switch is off`() = withAgent(account = "perm-1", upload = { "perm-1" }) { agent, runner, _ ->
        agent.refresh()
        assertEquals(AgentEventsAccount.UNKNOWN, agent.account)
        assertEquals(0, runner.uploadAsks)
    }

    private fun withAgent(
        diesAtOnce: Boolean = false,
        storage: Boolean? = true,
        account: String? = null,
        upload: suspend () -> String? = { null },
        block: suspend (AgentEvents, FakeRunner, FakeSettings) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = FakeRunner(diesAtOnce).also { it.account = account }
        val settings = FakeSettings()
        val agent = AgentEvents(
            settings, runner, scope, FixedClock(), poll = 1.hours,
            uploadAccount = { runner.uploadAsks++; upload() },
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
        /** The sign-in's account `status --json` reports, and how often the upload account was asked. */
        @Volatile var account: String? = null
        @Volatile var uploadAsks = 0

        override suspend fun run(arguments: List<String>, input: String?, timeout: Duration?): Pair<Int, String> {
            calls += arguments
            input?.let { stdin = it }
            if (arguments != AgentEventsCommand.STATUS) return 0 to ""
            val server = serving?.takeIf { it.isAlive }?.let { """{"pid": ${it.pid()}, "tunnelReady": true}""" } ?: "null"
            val id = account?.let { ""","googleAccountId":"$it"""" }.orEmpty()
            return 0 to """{"home":"/h","tunnelId":"tunnel_x","tunnelKey":true,"googleSignedIn":true$id,"server":$server,"drive":{},"subscriptions":0}"""
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
