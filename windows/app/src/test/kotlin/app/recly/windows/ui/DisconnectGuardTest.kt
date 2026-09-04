package app.recly.windows.ui

import app.recly.windows.auth.RevokeResult
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import recly.core.DisconnectResult

/**
 * docs/03 "연결 해제" · docs/06: the decisions a disconnect makes at the moment it *runs*, which is
 * not the moment the dialog was opened. The phone's `DisconnectGuardTest` runs the same cases.
 */
class DisconnectGuardTest {

    /** A recording that is live has no job yet, so the core's own `Busy` guard does not see it. */
    @Test
    fun `a live recording blocks the confirm rather than being stopped for the user`() {
        assertFalse(DisconnectPrompt(unuploaded = 2, recording = true).canConfirm)
        assertEquals(Str.DISCONNECT_STOP_RECORDING, DisconnectPrompt(0, recording = true).blocker)
        assertTrue(DisconnectPrompt(unuploaded = 2).canConfirm)
        assertNull(DisconnectPrompt(0).blocker)
    }

    @Test
    fun `the same answer is given again at the moment the disconnect runs`() {
        assertEquals(UiMessage.Res(Str.DISCONNECT_STOP_RECORDING), DisconnectGuard.liveBlocker(true))
        assertNull(DisconnectGuard.liveBlocker(false))
    }

    /**
     * The dialog may stand while a recording finishes, so the confirm re-reads the state and
     * re-presents when a warning appeared that the dialog never showed. Warnings that only lessened
     * do not re-ask, and `recording` is the live guards' business.
     */
    @Test
    fun `a confirm re-presents only when the fresh state warns more than the dialog did`() {
        val shown = DisconnectPrompt(unuploaded = 1)
        assertTrue(DisconnectPrompt(unuploaded = 2).warnsMore(shown))
        assertFalse(DisconnectPrompt(unuploaded = 1).warnsMore(shown), "nothing changed")
        assertFalse(DisconnectPrompt(unuploaded = 0).warnsMore(shown), "a lessened warning stands")
        assertFalse(
            DisconnectPrompt(unuploaded = 1, recording = true).warnsMore(shown),
            "recording has its own live guard",
        )
    }

    /**
     * The regression: a disconnect whose local half failed left the phase owed and the tokens gone.
     * A retry that revoked again would take away a grant a *new* sign-in had made — so a retry does
     * the clean-up it owes and nothing else.
     */
    @Test
    fun `a retry of an owed disconnect revokes nothing`() {
        assertTrue(DisconnectGuard.revokes(DisconnectPhase.NONE, signedIn = true))
        assertFalse(DisconnectGuard.revokes(DisconnectPhase.REVOKED_CLEANUP_OWED, signedIn = true))
        assertFalse(DisconnectGuard.revokes(DisconnectPhase.NONE, signedIn = false))
    }

    /**
     * The other end of that rule. A phase written *before* the revoke says nothing about whether the
     * grant went, so the token store answers it: still holding a refresh token is a revoke that
     * never happened and is tried again; not holding one is a revoke that got as far as clearing it,
     * and the retry goes on to the clean-up it owes.
     */
    @Test
    fun `a retry of a pending revoke tries again only while a refresh token is still here`() {
        assertTrue(DisconnectGuard.revokes(DisconnectPhase.REVOKE_PENDING, signedIn = true))
        assertFalse(DisconnectGuard.revokes(DisconnectPhase.REVOKE_PENDING, signedIn = false))
    }

    /**
     * The whole of the machine, in the order a disconnect walks it: nothing owed, then pending
     * *before* the credentials are touched, then the clean-up owed, then nothing again.
     */
    @Test
    fun `the phase is written before the revoke and again after it`() = runTest {
        val written = mutableListOf<DisconnectPhase>()

        val revoked = DisconnectGuard.revoking({ written += it; true }) {
            assertEquals(
                listOf(DisconnectPhase.REVOKE_PENDING),
                written,
                "pending before a single credential is deleted",
            )
            "revoked"
        }

        assertEquals("revoked", revoked)
        assertEquals(
            listOf(DisconnectPhase.REVOKE_PENDING, DisconnectPhase.REVOKED_CLEANUP_OWED),
            written,
        )
    }

    /** A revoke that never came back leaves it pending: what happened to the grant is not known. */
    @Test
    fun `a revoke that threw leaves the phase pending`() = runTest {
        val written = mutableListOf<DisconnectPhase>()

        val thrown = runCatching {
            DisconnectGuard.revoking({ written += it; true }) { error("network") }
        }

        assertTrue(thrown.isFailure)
        assertEquals(listOf(DisconnectPhase.REVOKE_PENDING), written)
    }

    /** End to end: NONE → REVOKE_PENDING → REVOKED_CLEANUP_OWED → NONE, and nothing skipped. */
    @Test
    fun `a disconnect that finished walks every phase in order`() = runTest {
        val written = mutableListOf<DisconnectPhase>()

        DisconnectGuard.revoking({ written += it; true }) { Unit }
        DisconnectGuard.owingCleanup({ written += it; true }) {
            DisconnectResult(deletedRecordings = 0, busyRecordings = emptyList())
        }

        assertEquals(
            listOf(
                DisconnectPhase.REVOKE_PENDING,
                DisconnectPhase.REVOKED_CLEANUP_OWED,
                DisconnectPhase.REVOKED_CLEANUP_OWED,
                DisconnectPhase.NONE,
            ),
            written,
        )
    }

    /**
     * The regression the phase alone could not catch. `signOut` on the failure branch deletes the
     * refresh token that [DisconnectGuard.revokes] reads to decide whether the revoke ever happened,
     * so a process killed between that deletion and the phase write came back with REVOKE_PENDING
     * and no token — read as "the revoke got as far as clearing it", which let the clean-up run on
     * to NONE and the flow report success over a grant Google was still listing. The debt is
     * therefore written first, and it is what outlives the token.
     */
    @Test
    fun `a revoke that failed owes the debt before the token it is about is deleted`() = runTest {
        val order = mutableListOf<String>()

        val outcome = DisconnectGuard.revoking({ order += "phase.$it"; true }) {
            DisconnectGuard.owingDebt(
                owe = { order += "debt.$it"; true },
                revoke = { RevokeResult.Failed("network") },
                forgetTokens = { order += "signOut" },
            )
        }

        assertIs<RevokeResult.Failed>(outcome)
        assertEquals(
            listOf("phase.REVOKE_PENDING", "debt.true", "signOut", "phase.REVOKED_CLEANUP_OWED"),
            order,
        )
    }

    /** Only Google taking the grant away settles it; having nothing to revoke settles nothing. */
    @Test
    fun `neither a revoke that succeeded nor one with no grant touches a standing debt`() = runTest {
        val owed = mutableListOf<Boolean>()
        var forgotten = false

        val revoked = DisconnectGuard.owingDebt(
            owe = { owed += it; true },
            revoke = { RevokeResult.Revoked },
            forgetTokens = { forgotten = true },
        )

        assertEquals(RevokeResult.Revoked, revoked)
        assertEquals(emptyList(), owed, "a revoke of one account must not clear another account's debt")
        assertFalse(forgotten, "a revoke that succeeded cleared the tokens twice")

        owed.clear()
        DisconnectGuard.owingDebt(
            owe = { owed += it; true },
            revoke = { RevokeResult.NotSignedIn },
            forgetTokens = { forgotten = true },
        )

        assertEquals(emptyList(), owed, "a disconnect with no grant to take away wrote a debt")
    }

    /**
     * The store half of the same rule. `java.util.prefs` commits on a flush, and a flush that threw
     * used to be nothing at all: the revoke went ahead, the credentials went with it, and nothing on
     * disk said a disconnect had ever started. So a phase that would not save revokes nothing.
     */
    @Test
    fun `a phase that would not save revokes nothing`() = runTest {
        var revoked = false

        val outcome = DisconnectGuard.revoking({ false }) {
            revoked = true
            RevokeResult.Revoked
        }

        assertNull(outcome, "a disconnect the store refused reported an outcome")
        assertFalse(revoked, "the revoke ran over a phase that is not on disk")
    }

    /**
     * The same for the debt, and for the same reason the debt is written first: `signOut` deletes
     * the token the retry reads, so a debt that is not on disk is one the retry cannot know about.
     * The grant is left standing, the token is left where it is, and the phase stays pending — which
     * is a retry that revokes again.
     */
    @Test
    fun `a debt that would not save signs nothing out`() = runTest {
        val written = mutableListOf<DisconnectPhase>()
        var forgotten = false

        val outcome = DisconnectGuard.revoking({ written += it; true }) {
            DisconnectGuard.owingDebt(
                owe = { false },
                revoke = { RevokeResult.Failed("network") },
                forgetTokens = { forgotten = true },
            )
        }

        assertNull(outcome)
        assertFalse(forgotten, "the token the retry reads was deleted over a debt that is not on disk")
        assertEquals(
            listOf(DisconnectPhase.REVOKE_PENDING),
            written,
            "a revoke that was not recorded moved the phase on to the clean-up",
        )
    }

    /** Nothing of this PC is deleted over a phase that would not say a clean-up was owed. */
    @Test
    fun `a clean-up is not run over a phase that would not save`() = runTest {
        var cleaned = false

        val result = DisconnectGuard.owingCleanup({ false }) {
            cleaned = true
            DisconnectResult(deletedRecordings = 0, busyRecordings = emptyList())
        }

        assertNull(result, "a clean-up the store refused reported a result")
        assertFalse(cleaned, "the keys and the queue were deleted over a phase that is not on disk")
    }

    /**
     * The one write that is *not* refused over: by the time the revoke has come back the credentials
     * are gone either way, and stopping there would leave the PC with nothing cleaned up. A phase
     * left at REVOKE_PENDING with no token is read as "the revoke happened" by [DisconnectGuard.revokes]
     * anyway, so the disconnect carries on and the clean-up runs.
     */
    @Test
    fun `a phase that would not save after the revoke does not undo it`() = runTest {
        val written = mutableListOf<DisconnectPhase>()

        val outcome = DisconnectGuard.revoking(
            persist = { phase ->
                written += phase
                phase == DisconnectPhase.REVOKE_PENDING
            },
        ) { RevokeResult.Revoked }

        assertEquals(RevokeResult.Revoked, outcome, "a revoke that happened was reported as refused")
        assertEquals(
            listOf(DisconnectPhase.REVOKE_PENDING, DisconnectPhase.REVOKED_CLEANUP_OWED),
            written,
        )
    }

    /** The same tolerance at the other end: a clean-up that ran is a clean-up that ran. */
    @Test
    fun `a phase that would not clear leaves the clean-up owed over one that has run`() = runTest {
        val done = DisconnectResult(deletedRecordings = 2, busyRecordings = emptyList())

        val result = DisconnectGuard.owingCleanup(
            persist = { phase -> phase == DisconnectPhase.REVOKED_CLEANUP_OWED },
        ) { done }

        assertEquals(done, result)
    }

    /**
     * The restart the debt exists for: REVOKE_PENDING with the token gone sends the retry straight
     * to the clean-up, which finishes and clears the phase — so the debt, which survived both the
     * token and the process, is the only thing left saying the grant is still standing, and the line
     * says it rather than "disconnected".
     */
    @Test
    fun `a restart with the debt owed cleans up and still says the grant is standing`() = runTest {
        val written = mutableListOf<DisconnectPhase>()
        // What the crash left on disk: pending, no refresh token, and the debt.
        val debt = true

        assertFalse(
            DisconnectGuard.revokes(DisconnectPhase.REVOKE_PENDING, signedIn = false),
            "the retry revoked a grant it holds no token for",
        )
        val result = DisconnectGuard.owingCleanup({ written += it; true }) {
            DisconnectResult(deletedRecordings = 0, busyRecordings = emptyList())
        }

        assertEquals(
            listOf(DisconnectPhase.REVOKED_CLEANUP_OWED, DisconnectPhase.NONE),
            written,
            "the clean-up the retry owes did not run to the end",
        )
        assertEquals(
            Str.DISCONNECT_STILL_LISTED.message(),
            DisconnectGuard.completion(
                revoked = null,
                cleanupFailure = null,
                result = result,
                stillListed = debt,
            ),
            "a clean-up that finished reported plain success over an owed debt",
        )
    }

    /** Everything the row can end on, in the order the user can do something about it. */
    @Test
    fun `the completion line never says plain success over an owed debt`() {
        val clean = DisconnectResult(deletedRecordings = 0, busyRecordings = emptyList())
        val deleted = DisconnectResult(deletedRecordings = 2, busyRecordings = emptyList())
        val busy = DisconnectResult(deletedRecordings = 1, busyRecordings = listOf("rec-2"))

        assertEquals(
            Str.DISCONNECT_REVOKE_FAILED.message("network"),
            DisconnectGuard.completion(RevokeResult.Failed("network"), null, clean, stillListed = true),
        )
        assertEquals(
            Str.DISCONNECT_CLEANUP_FAILED.message("disk"),
            DisconnectGuard.completion(RevokeResult.Revoked, "disk", null, stillListed = false),
        )
        assertEquals(
            Str.DISCONNECT_BUSY.message(1),
            DisconnectGuard.completion(RevokeResult.Revoked, null, busy, stillListed = false),
        )
        assertEquals(
            Str.DISCONNECT_DELETED.message(2),
            DisconnectGuard.completion(RevokeResult.Revoked, null, deleted, stillListed = false),
        )
        assertEquals(
            Str.DISCONNECT_DONE.message(),
            DisconnectGuard.completion(RevokeResult.Revoked, null, clean, stillListed = false),
        )
        // The debt outlives the disconnect that could not pay it, so even a later one that deleted
        // what it was asked to says the grant is still there instead.
        assertEquals(
            Str.DISCONNECT_STILL_LISTED.message(),
            DisconnectGuard.completion(RevokeResult.Revoked, null, deleted, stillListed = true),
        )
    }

    /** The other end of the same rule: the account slot is the disconnect's until it has finished. */
    @Test
    fun `signing in is refused while a disconnect is owed`() {
        assertEquals(Str.DISCONNECT_PENDING_SIGN_IN, DisconnectGuard.signInBlocker(pending = true))
        assertNull(DisconnectGuard.signInBlocker(pending = false))
    }

    @Test
    fun `a phase this build does not know is not one it owes`() {
        assertEquals(DisconnectPhase.NONE, DisconnectPhase.of(null))
        assertEquals(DisconnectPhase.NONE, DisconnectPhase.of(""))
        assertEquals(DisconnectPhase.NONE, DisconnectPhase.of("SOMETHING_LATER"))
        assertEquals(
            DisconnectPhase.REVOKED_CLEANUP_OWED,
            DisconnectPhase.of("REVOKED_CLEANUP_OWED"),
        )
        assertEquals(DisconnectPhase.REVOKE_PENDING, DisconnectPhase.of("REVOKE_PENDING"))
        // Both halves are a disconnect that has not finished, and both keep the retry row on screen
        // and a second account out of the slot.
        assertTrue(DisconnectPhase.REVOKED_CLEANUP_OWED.owed)
        assertTrue(DisconnectPhase.REVOKE_PENDING.owed)
        assertFalse(DisconnectPhase.NONE.owed)
    }

    /** Owed before the clean-up is tried, cleared only once it has actually finished. */
    @Test
    fun `the phase is written before the clean-up and cleared after it`() = runTest {
        val written = mutableListOf<DisconnectPhase>()

        DisconnectGuard.owingCleanup({ written += it; true }) {
            assertEquals(listOf(DisconnectPhase.REVOKED_CLEANUP_OWED), written, "owed before the work")
            DisconnectResult(deletedRecordings = 2, busyRecordings = emptyList())
        }

        assertEquals(listOf(DisconnectPhase.REVOKED_CLEANUP_OWED, DisconnectPhase.NONE), written)
    }

    /** A throw inside the clean-up leaves it owed, which is what puts the retry row on screen. */
    @Test
    fun `a clean-up that threw leaves the phase owed`() = runTest {
        val written = mutableListOf<DisconnectPhase>()

        val thrown = runCatching {
            DisconnectGuard.owingCleanup({ written += it; true }) { error("disk") }
        }

        assertTrue(thrown.isFailure)
        assertEquals(listOf(DisconnectPhase.REVOKED_CLEANUP_OWED), written)
    }

    /** Returning is not finishing: a recording a RUNNING job would not let go of is still owed. */
    @Test
    fun `a clean-up that had to keep a busy recording stays owed`() = runTest {
        val written = mutableListOf<DisconnectPhase>()

        DisconnectGuard.owingCleanup({ written += it; true }) {
            DisconnectResult(deletedRecordings = 1, busyRecordings = listOf("rec-2"))
        }

        assertEquals(listOf(DisconnectPhase.REVOKED_CLEANUP_OWED), written)
    }

    /**
     * docs/03: the clean-up walks the recording directory, so nothing may start a capture while it
     * runs — and the gate is opened again however the disconnect ended, because a gate left shut is
     * a PC that can never record again.
     */
    @Test
    fun `the gate refuses starts for the whole of a disconnect and opens again after it`() = runTest {
        assertNull(DisconnectGate.startBlocker())

        val held = async {
            DisconnectGate.hold {
                assertTrue(DisconnectGate.busy)
                assertEquals(UiMessage.Res(Str.DISCONNECT_IN_PROGRESS), DisconnectGate.startBlocker())
                yield()
            }
        }
        held.await()

        assertFalse(DisconnectGate.busy)
        assertNull(DisconnectGate.startBlocker())
    }

    @Test
    fun `a disconnect that threw still opens the gate`() = runTest {
        runCatching { DisconnectGate.hold { error("revoke") } }

        assertFalse(DisconnectGate.busy)
    }

    /**
     * The regression the gate is held across the start for: a start suspends between the check and
     * the capture actually opening (it reads the workflow summaries first), and a disconnect that
     * slipped into that wait would walk the recording directory while the capture wrote into it. So
     * the disconnect waits for the start that is already inside, rather than interleaving with it.
     */
    @Test
    fun `a disconnect waits for a start that is already inside the gate`() = runTest {
        val order = mutableListOf<String>()
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val start = async {
            DisconnectGate.ifOpen {
                order += "start.begin"
                inside.complete(Unit)
                release.await()
                order += "start.end"
                "rec-1"
            }
        }
        inside.await()
        val disconnect = async { DisconnectGate.hold { order += "disconnect" } }
        // Long enough for the disconnect to have run if the gate had let it.
        repeat(10) { yield() }
        assertEquals(listOf("start.begin"), order, "the disconnect went ahead under a live start")

        release.complete(Unit)
        assertEquals("rec-1", start.await())
        disconnect.await()
        assertEquals(listOf("start.begin", "start.end", "disconnect"), order)
    }

    /** The other way round: a start that arrives during a disconnect is refused, not queued. */
    @Test
    fun `a start that finds the gate held is refused rather than waiting for it`() = runTest {
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val disconnect = async {
            DisconnectGate.hold {
                inside.complete(Unit)
                release.await()
            }
        }
        inside.await()

        assertNull(DisconnectGate.ifOpen { "rec-1" }, "a capture opened inside a disconnect")
        assertEquals(UiMessage.Res(Str.DISCONNECT_IN_PROGRESS), DisconnectGate.startBlocker())

        release.complete(Unit)
        disconnect.await()
        assertEquals("rec-1", DisconnectGate.ifOpen { "rec-1" })
    }
}
