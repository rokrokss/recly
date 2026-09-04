package app.recly.windows.ui

import app.recly.windows.auth.RevokeResult
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import recly.core.DisconnectResult

/**
 * docs/03 "연결 해제" · docs/06: the decisions a disconnect makes at the moment it *runs*, which is
 * not the moment [DisconnectPrompt] was built. A dialog is on screen for as long as the user leaves
 * it there, and a retry may be a whole launch later — so the two things that could make a disconnect
 * do the wrong thing are decided here and asked again by the model that is about to act. The phone's
 * twin is `ui/DisconnectGuard.kt`, RecKit's is `Jobs/Retention.swift`.
 */
object DisconnectGuard {

    /**
     * Whether this disconnect still has a Google grant of its own to take away.
     *
     * The regression: a disconnect whose *local* half failed leaves [DisconnectPhase.REVOKED_CLEANUP_OWED]
     * on disk and the tokens cleared — the grant is already gone. If the user then signed in again,
     * the retry saw an account and revoked it, taking away a grant this disconnect was never about.
     * So a retry of that phase skips the revoke entirely and does the clean-up it actually owes, and
     * [signInBlocker] is the other end of the same rule: there is no second account to be signed in
     * with while a disconnect is owed.
     *
     * [DisconnectPhase.REVOKE_PENDING] is the other way round — the phase was written *before* the
     * revoke, so the process may have died with the grant still standing and the refresh token still
     * here. A refresh token that is still here is a revoke that never happened, so the retry tries it
     * again; one that is gone means the revoke got as far as clearing it, and the retry goes on to
     * the clean-up.
     */
    fun revokes(phase: DisconnectPhase, signedIn: Boolean): Boolean =
        signedIn && phase != DisconnectPhase.REVOKED_CLEANUP_OWED

    /**
     * Why signing in — or out — is refused while a disconnect is still owed, or null when it is not.
     *
     * Both ends of the same rule: the account slot belongs to the disconnect until it has finished.
     * A plain sign-out after [DisconnectPhase.REVOKE_PENDING] would delete the refresh token
     * [revokes] reads to tell "revoke again" from "already revoked", and the grant would be left
     * standing with no debt written.
     */
    fun signInBlocker(pending: Boolean): Str? =
        if (pending) Str.DISCONNECT_PENDING_SIGN_IN else null

    /**
     * What the shell says when the settings store would not commit the phase or the debt. Nothing
     * that deletes a credential was run, so there is nothing to undo and nothing else to report.
     */
    fun saveFailed(): UiMessage = Str.DISCONNECT_SAVE_FAILED.message()

    /**
     * What to say instead of disconnecting when a capture is live *now*, or null when none is.
     *
     * [DisconnectPrompt.recording] is read when the warning opens, and a recording can start while
     * it stands — from the tray menu, from the popup, from a meeting the detector spotted.
     * Confirming then would run "also delete the recordings" over the directory the recorder is
     * still writing into, so the recorder is read once more here and the answer is docs/12's: say
     * what is in the way, never stop it for them.
     */
    fun liveBlocker(recording: Boolean): UiMessage? =
        if (recording) Str.DISCONNECT_STOP_RECORDING.message() else null

    /**
     * The Google half of a disconnect with the phase written around it: pending *before* the revoke
     * is tried, owed only once it has come back.
     *
     * The order is the whole point, and it is the same one [owingCleanup] keeps for the local half.
     * Writing nothing before the revoke meant every credential this PC holds could be deleted —
     * `revokeAccess` clears the tokens on the way out, and clears them again on its failure branch —
     * while the store still said `NONE`. A process killed in that window came back signed out, with
     * the queue, the keys and the sync state all still here and nothing on screen offering to finish
     * the job. [DisconnectPhase.REVOKE_PENDING] is what is on disk for the whole of it.
     *
     * A throw is left to the caller and leaves the phase pending, which is the honest reading: it is
     * not known whether the grant went, and [revokes] asks the token store rather than guessing.
     *
     * A phase that could not be *flushed* stops the whole thing before anything is revoked.
     * `java.util.prefs` writes on a timer of its own, so the flush is what commits it, and its
     * refusal used to be nothing at all: the credentials went anyway and nothing on disk said a
     * disconnect had ever started. So a write that did not commit is null — nothing run, nothing
     * deleted, the PC exactly as it was — and so is a [revoke] that returned null for the same
     * reason (the debt it owed would not commit either).
     */
    suspend fun <T : Any> revoking(
        persist: suspend (DisconnectPhase) -> Boolean,
        revoke: suspend () -> T?,
    ): T? {
        if (!persist(DisconnectPhase.REVOKE_PENDING)) return null
        // A revoke that refused to be recorded left the token where it was, so the phase stays
        // pending and the retry revokes again.
        val result = revoke() ?: return null
        // The result is deliberately not propagated: by here the credentials are gone either way,
        // and refusing to go on would leave the PC with nothing cleaned up. A phase left at
        // REVOKE_PENDING with no token is read as "the revoke happened" by [revokes] anyway.
        persist(DisconnectPhase.REVOKED_CLEANUP_OWED)
        return result
    }

    /**
     * The revoke itself, with the debt written around it: a failure is on disk *before* the tokens
     * it is about are deleted.
     *
     * The order is the whole point, as it is in [revoking] and [owingCleanup], and the regression is
     * one the phase alone could not catch. `signOut` on the failure branch deletes the refresh
     * token; a process killed between that and the phase write came back with
     * [DisconnectPhase.REVOKE_PENDING] and no token, so [revokes] — which reads the token store to
     * decide whether the revoke ever happened — read the missing token as "it did", let the clean-up
     * run on to [DisconnectPhase.NONE], and the flow reported success over a Google grant that was
     * still standing. So the failure is written first and survives the token it is about; nothing
     * but a successful revoke or the user clears it.
     *
     * [RevokeResult.NotSignedIn] settles nothing either way — there was no grant to take away — so
     * it leaves whatever was owed owed.
     *
     * A debt that could not be flushed stops the sign-out for the same reason [revoking] stops the
     * revoke: `signOut` deletes the token the retry reads, and a debt that is not on disk is one the
     * retry cannot know about. So it returns null — the grant is left standing, the token is left
     * where it is and the phase stays pending, which is a retry that revokes again.
     */
    suspend fun owingDebt(
        owe: suspend (Boolean) -> Boolean,
        revoke: suspend () -> RevokeResult,
        forgetTokens: suspend () -> Unit,
    ): RevokeResult? {
        val outcome = revoke()
        when (outcome) {
            is RevokeResult.Failed -> {
                if (!owe(true)) return null
                forgetTokens()
            }

            // A revoke that succeeded says nothing about a debt already standing: the app keeps no
            // account identity, so the grant just taken away may belong to another account than
            // the one still listing Recly (Sol P1-windows r3). Only the user's own word clears it.
            RevokeResult.Revoked -> Unit
            RevokeResult.NotSignedIn -> Unit
        }
        return outcome
    }

    /**
     * What the Disconnect row says once it is over, in the order the user can do something about:
     * the two failures first, then the recordings a running job would not let go of, then the grant
     * Google is still listing, and only then the plain success.
     *
     * [stillListed] is why this is a function rather than a `when` in the shell: a disconnect that
     * *skipped* the revoke — the restart path, where [DisconnectPhase.REVOKE_PENDING] and a missing
     * token send it straight to the clean-up — has no [RevokeResult] to read, and saying
     * "Disconnected" over an owed debt is the bug this whole guard is about.
     */
    fun completion(
        revoked: RevokeResult?,
        cleanupFailure: String?,
        result: DisconnectResult?,
        stillListed: Boolean,
    ): UiMessage = when {
        revoked is RevokeResult.Failed -> Str.DISCONNECT_REVOKE_FAILED.message(revoked.reason)
        cleanupFailure != null -> Str.DISCONNECT_CLEANUP_FAILED.message(cleanupFailure)
        result != null && result.busyRecordings.isNotEmpty() ->
            Str.DISCONNECT_BUSY.message(result.busyRecordings.size)

        stillListed -> Str.DISCONNECT_STILL_LISTED.message()
        result != null && result.deletedRecordings > 0 ->
            Str.DISCONNECT_DELETED.message(result.deletedRecordings)

        else -> Str.DISCONNECT_DONE.message()
    }

    /**
     * The local half of a disconnect with the phase written around it: owed *before* it is tried,
     * cleared only once it has actually finished.
     *
     * It is a function of its own so the order can be tested without a shell, and the order is the
     * whole point. Writing the phase after `core.disconnect` returned meant a process that died
     * inside the clean-up — or a clean-up that never returned at all — came back with the tokens
     * gone, every key and the whole queue still on the PC, and nothing on screen saying so. A throw
     * is left to the caller for the same reason: it leaves the phase owed.
     *
     * Returning is not the same as having finished, either: [DisconnectResult.busyRecordings] are
     * the ones a `RUNNING` job would not let go of, and the core deliberately kept them *and* their
     * queue rows for it. That clean-up is still owed — the Disconnect row is what runs the rest of
     * it once the job has finished — so the phase is only cleared when the result has none. Without
     * "also delete the recordings" the list is always empty and the phase clears as it always did.
     *
     * @return null when the phase would not commit, in which case the clean-up was never run — the
     *   keys, the queue and the sync state are all still here, which is the state the phase that
     *   could not be written was going to be the record of.
     */
    suspend fun owingCleanup(
        persist: suspend (DisconnectPhase) -> Boolean,
        cleanup: suspend () -> DisconnectResult,
    ): DisconnectResult? {
        if (!persist(DisconnectPhase.REVOKED_CLEANUP_OWED)) return null
        val result = cleanup()
        // A phase that would not clear leaves the clean-up owed over one that has already run: the
        // Disconnect row stays, and running it again over a PC that is already clean is what the
        // retry does anyway.
        if (result.busyRecordings.isEmpty()) persist(DisconnectPhase.NONE)
        return result
    }
}

/**
 * docs/03 "연결 해제" · docs/06: how far the last disconnect got. It is persisted because the retry
 * may be a whole launch later — the tokens are already gone by then, so this is the only thing that
 * keeps the Disconnect row on screen and keeps a second account out of the slot until the disconnect
 * has finished both of its halves.
 */
enum class DisconnectPhase {

    /** Nothing owed: no disconnect has run, or the last one finished. */
    NONE,

    /**
     * The revoke has been asked for and has not come back. Written before it, because the revoke is
     * what deletes this PC's credentials and a phase written after it is a phase that can be lost
     * with them. On the next launch it is not known whether the grant went, so the retry asks the
     * token store: a refresh token still here is revoked again, one that is gone goes to the
     * clean-up ([DisconnectGuard.revokes]).
     */
    REVOKE_PENDING,

    /** The Google grant is gone and the tokens with it; the local clean-up has not succeeded. */
    REVOKED_CLEANUP_OWED,

    ;

    /** The one question the rest of the app asks of it: is a disconnect still unfinished? */
    val owed: Boolean get() = this != NONE

    companion object {
        /** A name this build does not know is not a phase it owes; so is a store that is empty. */
        fun of(name: String?): DisconnectPhase = entries.firstOrNull { it.name == name } ?: NONE
    }
}

/**
 * The app-wide "a disconnect is running" flag, and the lock that makes it one at a time.
 *
 * docs/03's second half — "also delete the recordings" — walks the recording directory, and the
 * check that nothing is recording is made before the revoke, which is a network round trip. The tray
 * menu, the popup and the meeting detector can all start a capture inside that wait; it has no job
 * yet, so the core's own `Busy` guard does not see it, and the clean-up would delete the file the
 * recorder is still writing into. So every start on this PC opens the capture *inside* [ifOpen], and
 * the disconnect holds the lock from before the revoke until after the clean-up: the two can neither
 * interleave nor overlap, whichever got here first.
 */
object DisconnectGate {

    private val mutex = Mutex()

    @Volatile
    private var disconnecting = false

    /** True while [hold] runs. */
    val busy: Boolean get() = disconnecting

    /** Why a recording may not start right now, or null when nothing is in the way. */
    fun startBlocker(): UiMessage? =
        if (disconnecting) Str.DISCONNECT_IN_PROGRESS.message() else null

    /**
     * Runs [start] with the gate held for the whole of it, or refuses — null, nothing run — when it
     * is not free.
     *
     * [startBlocker] on its own is a reading and not a promise: a start suspends between it and the
     * capture actually opening (the workflow summaries are read from the database first), and a
     * disconnect that took the gate inside that wait would be walking the recording directory while
     * the capture wrote into it. So the last check and the start are the same critical section, and
     * this is it.
     *
     * It is [Mutex.tryLock] and not [Mutex.withLock] because a start that arrives during a
     * disconnect is refused rather than queued behind it: docs/12's answer to something being in the
     * way is to say what it is, and a capture that opened a minute later, after the clean-up, is not
     * the one the user asked for.
     */
    suspend fun <T : Any> ifOpen(start: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            start()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Runs [block] with every recording start refused for the whole of it, one disconnect at a time.
     * The flag is cleared in a `finally`, because a gate left shut is a PC that can never record
     * again.
     */
    suspend fun <T> hold(block: suspend () -> T): T = mutex.withLock {
        disconnecting = true
        try {
            block()
        } finally {
            disconnecting = false
        }
    }
}
