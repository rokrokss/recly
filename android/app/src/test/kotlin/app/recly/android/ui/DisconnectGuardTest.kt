package app.recly.android.ui

import app.recly.android.R
import app.recly.android.auth.RevokeResult
import app.recly.android.core.UiMessage
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import recly.core.DisconnectResult

/**
 * docs/03 "연결 해제" · docs/06: the three decisions a disconnect makes without a screen, and the
 * two regressions they exist for — confirming over a live recorder, and a retry revoking a grant
 * this disconnect was never about.
 */
class DisconnectGuardTest {

    /**
     * A capture that is running has no job yet — the job is made at the stop — so the core's own
     * `Busy` guard, which reads the queue, does not cover it: "also delete the recordings" would
     * take the directory out from under the recorder still writing into it.
     */
    @Test
    fun `the warning cannot be confirmed while a recording is running`() {
        val prompt = DisconnectPrompt(unuploaded = 2, recording = true)

        assertFalse(prompt.canConfirm)
        assertEquals(R.string.disconnect_stop_recording, prompt.blocker)
    }

    @Test
    fun `an idle recorder blocks nothing and says nothing`() {
        val prompt = DisconnectPrompt(unuploaded = 2)

        assertTrue(prompt.canConfirm)
        assertNull(prompt.blocker)
    }

    /**
     * The dialog stands for as long as the user leaves it there, and a tile, a widget or the watch
     * can start a capture while it does — so the recorder is read again at the moment the
     * disconnect runs, and the answer is docs/12's: say what is in the way, never stop it for them.
     */
    @Test
    fun `a recording that started while the dialog stood refuses the disconnect`() {
        assertEquals(
            UiMessage.Res(R.string.disconnect_stop_recording),
            DisconnectGuard.liveBlocker(recording = true),
        )
        assertNull(DisconnectGuard.liveBlocker(recording = false))
    }

    /**
     * The dialog may stand while a recording started elsewhere finishes and joins the queue, so the
     * confirm re-reads the state and re-presents when a warning appeared that the dialog never
     * showed. Warnings that only lessened do not re-ask, and `recording` is the live guards'
     * business ([DisconnectPrompt.canConfirm], [DisconnectGuard.liveBlocker]).
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

    /** The ordinary disconnect: signed in, nothing owed, so the grant is this one's to take away. */
    @Test
    fun `a first disconnect revokes the grant`() {
        assertTrue(DisconnectGuard.revokes(DisconnectPhase.NONE, signedIn = true))
    }

    /**
     * The regression. A disconnect whose local half failed left the account cleared and the phase
     * owed — the grant is already gone. A retry that saw an account and revoked it would be taking
     * away a grant this disconnect was never about, so it skips the revoke and does the clean-up
     * it actually owes.
     */
    @Test
    fun `a clean-up that is owed retries it without revoking again`() {
        assertFalse(DisconnectGuard.revokes(DisconnectPhase.REVOKED_CLEANUP_OWED, signedIn = false))
        assertFalse(DisconnectGuard.revokes(DisconnectPhase.REVOKED_CLEANUP_OWED, signedIn = true))
    }

    /**
     * The other half of the machine, and the reason [DisconnectPhase.REVOKE_PENDING] exists at all:
     * the phase was written *before* the revoke, so a process that died in there says nothing about
     * whether the grant went. The account store is what knows — one that is still here is a revoke
     * that never happened, and it is tried again.
     */
    @Test
    fun `a pending revoke is tried again while the account survives it`() {
        assertTrue(DisconnectGuard.revokes(DisconnectPhase.REVOKE_PENDING, signedIn = true))
    }

    /**
     * And the same phase with the account gone is a revoke that got as far as clearing it — both
     * `revokeAccess` and its failure branch sign out — so the retry owes only the clean-up.
     */
    @Test
    fun `a pending revoke whose account is gone goes on to the clean-up`() {
        assertFalse(DisconnectGuard.revokes(DisconnectPhase.REVOKE_PENDING, signedIn = false))
    }

    /** Nothing to revoke when nothing is signed in — the sign-out path leaves this state too. */
    @Test
    fun `a signed-out device revokes nothing`() {
        assertFalse(DisconnectGuard.revokes(DisconnectPhase.NONE, signedIn = false))
    }

    /**
     * The order the whole phase exists for: `revokeAccess` clears this phone's account on its way
     * through, so a phase written after it is one that can be lost with it. A process killed in
     * that window came back signed out with the queue, the keys and the sync state all still here
     * and nothing on screen offering to finish the job.
     */
    @Test
    fun `the revoke is pending before it is tried and owed once it has come back`() = runTest {
        val written = mutableListOf<DisconnectPhase>()
        var phaseAtRevoke: DisconnectPhase? = null

        val result = DisconnectGuard.revoking(persist = { written += it }) {
            phaseAtRevoke = written.last()
            RevokeResult.Revoked
        }

        assertEquals(DisconnectPhase.REVOKE_PENDING, phaseAtRevoke)
        assertEquals(
            listOf(DisconnectPhase.REVOKE_PENDING, DisconnectPhase.REVOKED_CLEANUP_OWED),
            written,
        )
        assertEquals(RevokeResult.Revoked, result)
    }

    /**
     * A revoke that threw is not a revoke that is known to have failed: it is not known whether the
     * grant went, so the phase stays pending and [DisconnectGuard.revokes] asks the account store
     * on the retry rather than guessing.
     */
    @Test
    fun `a revoke that throws leaves the phase pending`() = runTest {
        val written = mutableListOf<DisconnectPhase>()

        assertFailsWith<IllegalStateException> {
            DisconnectGuard.revoking(persist = { written += it }) { error("the network went away") }
        }

        assertEquals(listOf(DisconnectPhase.REVOKE_PENDING), written)
        assertTrue(written.last().owed)
    }

    /**
     * The debt's own ordering, and the regression the phase alone could not catch: `signOut` on the
     * failure branch deletes the account, and with it every way of telling a revoke that happened
     * from one that never did. So the debt is on disk *before* the account it is about.
     */
    @Test
    fun `a failed revoke owes the debt before the account is forgotten`() = runTest {
        val order = mutableListOf<String>()

        val outcome = DisconnectGuard.owingDebt(
            owe = { order += "owe($it)" },
            revoke = { RevokeResult.Failed("network") },
            forgetAccount = { order += "signOut" },
        )

        assertEquals(listOf("owe(true)", "signOut"), order)
        assertEquals(RevokeResult.Failed("network"), outcome)
    }

    /**
     * The app keeps no account identity of its own, so a revoke that succeeded says nothing about a
     * debt already standing — the grant just taken away may belong to another account than the one
     * still listing Recly. Neither outcome clears it, and neither signs out here: `revokeAccess`
     * has already done that itself when it worked.
     */
    @Test
    fun `a revoke that worked or had nothing to take clears no debt and forgets nothing`() = runTest {
        val cleared = mutableListOf<Boolean>()
        val forgotten = mutableListOf<String>()

        listOf(RevokeResult.Revoked, RevokeResult.NotSignedIn).forEach { outcome ->
            DisconnectGuard.owingDebt(
                owe = { cleared += it },
                revoke = { outcome },
                forgetAccount = { forgotten += "signOut" },
            )
        }

        assertEquals(emptyList(), cleared.toList())
        assertEquals(emptyList(), forgotten.toList())
    }

    /**
     * The store half of the same ordering. DataStore can refuse a write, and the phase is written
     * *before* the revoke precisely because a revoke that is not on disk cannot be retried — so a
     * write that threw takes the whole disconnect out with it, before a single credential is
     * touched. The PC and Apple twins return a Bool from their persist for this; here the throw is
     * already out of [DisconnectGuard.revoking] before the revoke is reached, and
     * `MainViewModel.disconnect` turns it into [DisconnectGuard.saveFailed].
     */
    @Test
    fun `a phase the store would not take revokes nothing`() = runTest {
        var revoked = false

        assertFailsWith<IOException> {
            DisconnectGuard.revoking(persist = { throw IOException("no space") }) {
                revoked = true
                RevokeResult.Revoked
            }
        }

        assertFalse(revoked, "the revoke ran over a phase that is not on disk")
    }

    /**
     * The same for the debt: `signOut` deletes the account the retry reads, so a debt that is not on
     * disk is one the retry cannot know about. The grant is left standing, the account is left where
     * it is, and the phase stays pending — which is a retry that revokes again.
     */
    @Test
    fun `a debt the store would not take forgets no account`() = runTest {
        val written = mutableListOf<DisconnectPhase>()
        var forgotten = false

        assertFailsWith<IOException> {
            DisconnectGuard.revoking(persist = { written += it }) {
                DisconnectGuard.owingDebt(
                    owe = { throw IOException("no space") },
                    revoke = { RevokeResult.Failed("network") },
                    forgetAccount = { forgotten = true },
                )
            }
        }

        assertFalse(forgotten, "the account the retry reads was deleted over a debt that is not on disk")
        assertEquals(
            listOf(DisconnectPhase.REVOKE_PENDING),
            written,
            "a revoke that was not recorded moved the phase on to the clean-up",
        )
    }

    /** And nothing of this phone is deleted over a phase that would not say a clean-up was owed. */
    @Test
    fun `a clean-up is not run over a phase the store would not take`() = runTest {
        var cleaned = false

        assertFailsWith<IOException> {
            DisconnectGuard.owingCleanup(persist = { throw IOException("no space") }) {
                cleaned = true
                DisconnectResult(deletedRecordings = 0, busyRecordings = emptyList())
            }
        }

        assertFalse(cleaned, "the keys and the queue were deleted over a phase that is not on disk")
    }

    /**
     * What the row says when it is over, in the order the user can act on. The debt comes after the
     * failures and the busy recordings, and before every kind of success — including the restart
     * path, which skipped the revoke and has no [RevokeResult] here at all.
     */
    @Test
    fun `the completion says what is still owed before it says done`() {
        val done = DisconnectResult(deletedRecordings = 0, busyRecordings = emptyList())
        val deleted = DisconnectResult(deletedRecordings = 2, busyRecordings = emptyList())
        val busy = DisconnectResult(deletedRecordings = 0, busyRecordings = listOf("rec-busy"))

        assertEquals(
            UiMessage.Res(R.string.disconnect_revoke_failed, listOf("network")),
            DisconnectGuard.completion(RevokeResult.Failed("network"), null, done, stillListed = true),
        )
        assertEquals(
            UiMessage.Res(R.string.disconnect_cleanup_failed, listOf("disk")),
            DisconnectGuard.completion(RevokeResult.Revoked, "disk", done, stillListed = true),
        )
        assertEquals(
            UiMessage.Res(R.string.disconnect_busy, listOf(1)),
            DisconnectGuard.completion(RevokeResult.Revoked, null, busy, stillListed = true),
        )
        // The restart path: nothing was revoked here, and the debt is the only thing that knows.
        assertEquals(
            UiMessage.Res(R.string.disconnect_still_listed),
            DisconnectGuard.completion(null, null, done, stillListed = true),
        )
        assertEquals(
            UiMessage.Res(R.string.disconnect_still_listed),
            DisconnectGuard.completion(RevokeResult.Revoked, null, deleted, stillListed = true),
        )
        assertEquals(
            UiMessage.Res(R.string.disconnect_deleted, listOf(2)),
            DisconnectGuard.completion(RevokeResult.Revoked, null, deleted, stillListed = false),
        )
        assertEquals(
            UiMessage.Res(R.string.disconnect_done),
            DisconnectGuard.completion(RevokeResult.Revoked, null, done, stillListed = false),
        )
    }

    /** The other end of the same rule: no second account goes in the slot while one is owed. */
    @Test
    fun `signing in is blocked while a disconnect is pending`() {
        assertEquals(R.string.disconnect_pending_sign_in, DisconnectGuard.signInBlocker(pending = true))
        assertNull(DisconnectGuard.signInBlocker(pending = false))
    }

    /**
     * The race the gate exists for. The recorder is read before `revokeAccess()`, which is a
     * network round trip; a tile, a widget or the watch starting a capture inside that wait made a
     * recording with no job — invisible to the core's `Busy` guard — that the clean-up would then
     * delete while it was still being written. So the flag goes up before the revoke, every start
     * asks it, and the recorder is read once more behind the gate before anything is deleted.
     */
    @Test
    fun `a start asked for during the revoke is refused, and the gate is still shut after it`() = runTest {
        val revoked = CompletableDeferred<Unit>()
        val blockers = mutableListOf<UiMessage?>()

        val disconnect = launch {
            DisconnectGate.hold {
                revoked.await()
                // Where the disconnect reads the recorder again, still behind the gate.
                blockers += DisconnectGate.startBlocker()
            }
        }
        // Lets the disconnect above run as far as its revoke, and no further.
        yield()
        // The tile tap that lands while the revoke is still in flight.
        blockers += DisconnectGate.startBlocker()
        revoked.complete(Unit)
        disconnect.join()

        val refused: UiMessage? = UiMessage.Res(R.string.disconnect_in_progress)
        assertEquals(listOf(refused, refused), blockers.toList())
    }

    /** Nothing is in the way when no disconnect is running — the gate is shut, never sticky. */
    @Test
    fun `a disconnect that throws still reopens the gate`() = runTest {
        assertNull(DisconnectGate.startBlocker())
        assertFailsWith<IllegalStateException> { DisconnectGate.hold<Unit> { error("revoke blew up") } }
        assertNull(DisconnectGate.startBlocker())
    }

    /**
     * The other regression: the phase was written after `core.disconnect` returned, so a process
     * that died inside the clean-up came back with the account cleared, every key still on the
     * phone and no Disconnect row to retry from.
     */
    @Test
    fun `the clean-up is owed before it is tried and stays owed when it throws`() = runTest {
        val written = mutableListOf<DisconnectPhase>()

        assertFailsWith<IllegalStateException> {
            DisconnectGuard.owingCleanup(persist = { written += it }) { error("the disk went away") }
        }

        assertEquals(listOf(DisconnectPhase.REVOKED_CLEANUP_OWED), written)
        assertTrue(written.last().owed)
    }

    @Test
    fun `the clean-up is cleared only once it has succeeded`() = runTest {
        val written = mutableListOf<DisconnectPhase>()

        val result = DisconnectGuard.owingCleanup(persist = { written += it }) {
            DisconnectResult(deletedRecordings = 3, busyRecordings = emptyList())
        }

        assertEquals(listOf(DisconnectPhase.REVOKED_CLEANUP_OWED, DisconnectPhase.NONE), written)
        assertEquals(3, result.deletedRecordings)
    }

    /**
     * A clean-up that returned is not always a clean-up that finished: a recording a `RUNNING` job
     * would not let go of is kept, and so are its queue rows, so this phone still holds what the
     * disconnect promised to remove. Clearing the phase there took the Disconnect row away and left
     * nobody to finish it once the job had.
     */
    @Test
    fun `a clean-up that had to keep a busy recording is still owed`() = runTest {
        val written = mutableListOf<DisconnectPhase>()

        val result = DisconnectGuard.owingCleanup(persist = { written += it }) {
            DisconnectResult(deletedRecordings = 1, busyRecordings = listOf("rec-busy"))
        }

        assertEquals(listOf(DisconnectPhase.REVOKED_CLEANUP_OWED), written)
        assertTrue(written.last().owed)
        assertEquals(listOf("rec-busy"), result.busyRecordings)
    }

    /**
     * Without "also delete the recordings" the core never even looks at them, so the list is empty
     * and there is nothing left owed — the ordinary disconnect still clears.
     */
    @Test
    fun `a clean-up that was not asked to delete recordings clears the phase`() = runTest {
        val written = mutableListOf<DisconnectPhase>()

        DisconnectGuard.owingCleanup(persist = { written += it }) {
            DisconnectResult(deletedRecordings = 0, busyRecordings = emptyList())
        }

        assertEquals(listOf(DisconnectPhase.REVOKED_CLEANUP_OWED, DisconnectPhase.NONE), written)
    }

    /** A store with nothing in it owes nothing, and neither does a phase this build cannot read. */
    @Test
    fun `an unknown or absent phase owes nothing`() {
        assertEquals(DisconnectPhase.NONE, DisconnectPhase.of(null))
        assertEquals(DisconnectPhase.NONE, DisconnectPhase.of("SOMETHING_A_LATER_BUILD_WROTE"))
        assertEquals(DisconnectPhase.REVOKE_PENDING, DisconnectPhase.of("REVOKE_PENDING"))
        assertEquals(DisconnectPhase.REVOKED_CLEANUP_OWED, DisconnectPhase.of("REVOKED_CLEANUP_OWED"))
        assertFalse(DisconnectPhase.NONE.owed)
        assertTrue(DisconnectPhase.REVOKE_PENDING.owed)
        assertTrue(DisconnectPhase.REVOKED_CLEANUP_OWED.owed)
    }

    /** Parity with Windows/Apple: the writes after the destructive step are tolerated. */
    @Test
    fun `a post-revoke write that fails does not undo the revoke or throw`() = runTest {
        val written = mutableListOf<DisconnectPhase>()
        var revoked = false
        val result = DisconnectGuard.revoking(
            persist = { phase ->
                if (phase == DisconnectPhase.REVOKED_CLEANUP_OWED) throw java.io.IOException("disk")
                written += phase
            },
            revoke = { revoked = true; "ok" },
        )
        assertEquals("ok", result)
        assertTrue(revoked)
        assertEquals(listOf(DisconnectPhase.REVOKE_PENDING), written)
    }

    @Test
    fun `clearing the phase after a clean-up is tolerated too`() = runTest {
        val written = mutableListOf<DisconnectPhase>()
        val result = DisconnectGuard.owingCleanup(
            persist = { phase ->
                if (phase == DisconnectPhase.NONE) throw java.io.IOException("disk")
                written += phase
            },
            cleanup = { DisconnectResult(deletedRecordings = 0, busyRecordings = emptyList()) },
        )
        assertEquals(0, result.deletedRecordings)
        assertEquals(listOf(DisconnectPhase.REVOKED_CLEANUP_OWED), written)
    }
}
