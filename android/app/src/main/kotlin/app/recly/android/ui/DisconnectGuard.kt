package app.recly.android.ui

import androidx.annotation.StringRes
import app.recly.android.R
import app.recly.android.auth.RevokeResult
import app.recly.android.core.UiMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import recly.core.DisconnectResult

/**
 * docs/03 "로그아웃 vs 연결 해제": the warning is not a yes/no, it is a few facts and a separate
 * question. [unuploaded] is one of them — how many recordings have never reached Drive and
 * would be left on this phone (principle 3: an original is not deleted by a decision about an
 * account). [recording] is what the dialog cannot let past, see [canConfirm].
 */
data class DisconnectPrompt(
    val unuploaded: Int,
    val recording: Boolean = false,
) {

    /**
     * Whether the disconnect may go ahead.
     *
     * A capture that is running has no job yet — the job is made at the stop — so the core's own
     * `Busy` guard, which looks at the queue, does not cover it: "also delete the recordings" would
     * take the directory out from under the recorder that is still writing into it. And a
     * disconnect that stopped the recording for the user would be answering a question nobody
     * asked, so the answer is to say what is in the way and let them stop it themselves.
     */
    val canConfirm: Boolean get() = !recording

    /** The line that says what is in the way, or null when nothing is. */
    @get:StringRes
    val blocker: Int? get() = if (recording) R.string.disconnect_stop_recording else null

    /**
     * True when this freshly read state carries a warning the dialog the user confirmed never
     * showed — a recording that finished since [shown] was built and grew the count. The confirm
     * re-presents with this instead of acting on a promise the dialog did not make. [recording] is
     * not compared: it has its own live guards ([canConfirm] on the dialog, and
     * [DisconnectGuard.liveBlocker] at run time).
     */
    fun warnsMore(shown: DisconnectPrompt): Boolean = unuploaded > shown.unuploaded
}

/**
 * docs/03 "연결 해제" · docs/06: the decisions a disconnect makes at the moment it *runs*, which is
 * not the moment [DisconnectPrompt] was built. A dialog is on screen for as long as the user leaves
 * it there, and a retry may be a whole launch later — so the two things that could make a
 * disconnect do the wrong thing are decided here and asked again by the ViewModel that is about to
 * act. The Apple twin is `DisconnectGuard`.
 */
object DisconnectGuard {

    /**
     * Whether this disconnect still has a Google grant of its own to take away.
     *
     * The regression: a disconnect whose *local* half failed leaves [DisconnectPhase.REVOKED_CLEANUP_OWED]
     * on disk and the account cleared — the grant is already gone. If the user then signed in
     * again, the retry saw an account and revoked it, taking away a grant this disconnect was never
     * about. So a retry of that phase skips the revoke entirely and does the clean-up it actually
     * owes, and [signInBlocker] is the other end of the same rule: there is no second account to be
     * signed in with while a disconnect is owed.
     *
     * [DisconnectPhase.REVOKE_PENDING] is the other way round — the phase was written *before* the
     * revoke, so the process may have died with the grant still standing and the stored account
     * still here. An account that is still here is a revoke that never happened, so the retry tries
     * it again; one that is gone means the revoke got as far as clearing it (`revokeAccess` signs
     * out on its way through, and the failure branch does too), and the retry goes on to the
     * clean-up.
     */
    fun revokes(phase: DisconnectPhase, signedIn: Boolean): Boolean =
        signedIn && phase != DisconnectPhase.REVOKED_CLEANUP_OWED

    /** Why signing in — and signing out, which would take the account the retry reads — is refused
     * while a disconnect is still owed, or null when it is not. */
    @StringRes
    fun signInBlocker(pending: Boolean): Int? =
        if (pending) R.string.disconnect_pending_sign_in else null

    /**
     * What to say instead of disconnecting when a capture is live *now*, or null when none is.
     *
     * [DisconnectPrompt.recording] is read when the warning opens, and a recording can start while
     * it stands — from the tile, the widget, the shortcut, the watch. Confirming then would run
     * "also delete the recordings" over the directory the recorder is still writing into, so the
     * recorder is read once more here and the answer is docs/12's: say what is in the way, never
     * stop it for them.
     */
    fun liveBlocker(recording: Boolean): UiMessage? =
        if (recording) UiMessage.Res(R.string.disconnect_stop_recording) else null

    /**
     * What the shell says when the settings store would not take the phase or the debt. The two are
     * written *before* the account they are about is deleted ([revoking], [owingDebt]), so a write
     * that threw is a disconnect that never started: nothing was revoked, nothing was deleted, and
     * there is nothing to report but the store.
     */
    val saveFailed: UiMessage = UiMessage.Res(R.string.disconnect_save_failed)

    /**
     * The Google half of a disconnect with the phase written around it: pending *before* the revoke
     * is tried, owed only once it has come back.
     *
     * The order is the whole point, and it is the same one [owingCleanup] keeps for the local half.
     * Writing nothing before the revoke meant every credential this phone holds could be deleted —
     * `revokeAccess` signs out on its way through, and the failure branch signs out too — while the
     * store still said `NONE`. A process killed in that window came back signed out, with the
     * queue and the tokens all still here and nothing on screen offering to finish
     * the job. [DisconnectPhase.REVOKE_PENDING] is what is on disk for the whole of it.
     *
     * A throw is left to the caller and leaves the phase pending, which is the honest reading: it
     * is not known whether the grant went, and [revokes] asks the account store rather than
     * guessing.
     *
     * A [persist] that threw — DataStore refusing the write — stops the whole thing before anything
     * is revoked, because the exception is on its way out before [revoke] is reached: no revoke, no
     * sign-out, the phone exactly as it was, and [saveFailed] is what the shell says about it. The
     * Windows and Apple twins return a Bool from their persist for the same rule.
     */
    suspend fun <T> revoking(
        persist: suspend (DisconnectPhase) -> Unit,
        revoke: suspend () -> T,
    ): T {
        persist(DisconnectPhase.REVOKE_PENDING)
        val result = revoke()
        // Tolerated: by now the credentials are gone either way, and stopping here would leave the
        // phone with nothing cleaned up. A phase still reading REVOKE_PENDING with no account is
        // what the retry expects (Windows/Apple do the same).
        tolerated { persist(DisconnectPhase.REVOKED_CLEANUP_OWED) }
        return result
    }

    /** Runs a write whose failure must not stop the disconnect; cancellation still propagates. */
    private suspend fun tolerated(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next launch reads the previous phase and takes the retry path.
        }
    }

    /**
     * The revoke itself, with the debt written around it: a failure is on disk *before* the account
     * it is about is deleted.
     *
     * The order is the whole point, as it is in [revoking] and [owingCleanup], and the regression is
     * one the phase alone could not catch. `signOut` on the failure branch deletes the stored
     * account; a process killed between that and the phase write came back with
     * [DisconnectPhase.REVOKE_PENDING] and no account, so [revokes] — which reads the account store
     * to decide whether the revoke ever happened — read the missing account as "it did", let the
     * clean-up run on to [DisconnectPhase.NONE], and the flow reported success over a Google grant
     * that was still standing. So the failure is written first and survives the account it is
     * about; nothing but the user's own word clears it.
     *
     * [RevokeResult.NotSignedIn] settles nothing either way — there was no grant to take away — so
     * it leaves whatever was owed owed.
     *
     * An [owe] that threw stops the sign-out for the same reason [revoking] stops the revoke:
     * `signOut` deletes the account the retry reads, and a debt that is not on disk is one the retry
     * cannot know about. The grant is left standing, the account is left where it is and the phase
     * stays pending — which is a retry that revokes again.
     */
    suspend fun owingDebt(
        owe: suspend (Boolean) -> Unit,
        revoke: suspend () -> RevokeResult,
        forgetAccount: suspend () -> Unit,
    ): RevokeResult {
        val outcome = revoke()
        when (outcome) {
            is RevokeResult.Failed -> {
                owe(true)
                forgetAccount()
            }

            // A revoke that succeeded says nothing about a debt already standing: the app keeps no
            // account identity once it is done, so the grant just taken away may belong to another
            // account than the one still listing Recly. Only the user's own word clears it.
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
     * [stillListed] is why this is a function rather than a `when` in the ViewModel: a disconnect
     * that *skipped* the revoke — the restart path, where [DisconnectPhase.REVOKE_PENDING] and a
     * cleared account send it straight to the clean-up — has no [RevokeResult] to read, and saying
     * "Disconnected" over an owed debt is the bug this whole guard is about.
     */
    fun completion(
        revoked: RevokeResult?,
        cleanupFailure: String?,
        result: DisconnectResult?,
        stillListed: Boolean,
    ): UiMessage = when {
        revoked is RevokeResult.Failed ->
            UiMessage.Res(R.string.disconnect_revoke_failed, listOf(revoked.reason))

        cleanupFailure != null ->
            UiMessage.Res(R.string.disconnect_cleanup_failed, listOf(cleanupFailure))

        result != null && result.busyRecordings.isNotEmpty() ->
            UiMessage.Res(R.string.disconnect_busy, listOf(result.busyRecordings.size))

        stillListed -> UiMessage.Res(R.string.disconnect_still_listed)

        result != null && result.deletedRecordings > 0 ->
            UiMessage.Res(R.string.disconnect_deleted, listOf(result.deletedRecordings))

        else -> UiMessage.Res(R.string.disconnect_done)
    }

    /**
     * The local half of a disconnect with the phase written around it: owed *before* it is tried,
     * cleared only once it has actually finished.
     *
     * It is a function of its own so the order can be tested without a ViewModel, and the order is
     * the whole point. Writing the phase after `core.disconnect` returned meant a process that died
     * inside the clean-up — or a clean-up that never returned at all — came back with the account
     * cleared, every key and the whole queue still on the phone, and nothing on screen saying so.
     * A throw is left to the caller for the same reason: it leaves the phase owed.
     *
     * Returning is not the same as having finished, either: [DisconnectResult.busyRecordings] are
     * the ones a `RUNNING` job would not let go of, and the core deliberately kept them *and* their
     * queue rows for it. That clean-up is still owed — the Disconnect row is what runs the rest of
     * it once the job has finished — so the phase is only cleared when the result has none. Without
     * "also delete the recordings" the list is always empty and the phase clears as it always did.
     *
     * A [persist] that threw is the store refusing the phase rather than a clean-up that found
     * nothing: none of it was tried, so the queue and the tokens are all still here —
     * which is the state the phase that could not be written was going to be the record of.
     */
    suspend fun owingCleanup(
        persist: suspend (DisconnectPhase) -> Unit,
        cleanup: suspend () -> DisconnectResult,
    ): DisconnectResult {
        persist(DisconnectPhase.REVOKED_CLEANUP_OWED)
        val result = cleanup()
        // Tolerated as in [revoking]: the clean-up happened; a phase left owed only costs one
        // more "Disconnect" that finds nothing left to do.
        if (result.busyRecordings.isEmpty()) tolerated { persist(DisconnectPhase.NONE) }
        return result
    }
}

/**
 * docs/03 "연결 해제" · docs/06: how far the last disconnect got. It is persisted because the retry
 * may be a whole launch later — the account is already cleared by then, so this is the only thing
 * that keeps the Disconnect row on screen and keeps a second account out of the slot until the
 * disconnect has finished both of its halves.
 */
enum class DisconnectPhase {

    /** Nothing owed: no disconnect has run, or the last one finished. */
    NONE,

    /**
     * The revoke has been asked for and has not come back. Written before it, because the revoke is
     * what clears this phone's account and a phase written after it is a phase that can be lost
     * with it. On the next launch it is not known whether the grant went, so the retry asks the
     * account store: an account still here is revoked again, one that is gone goes to the clean-up
     * ([DisconnectGuard.revokes]).
     */
    REVOKE_PENDING,

    /** The Google grant is gone and the account with it; the local clean-up has not succeeded. */
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
 * check that nothing is recording is made before `revokeAccess()`, which is a network round trip. A
 * tile, a widget or the launcher shortcut can start a capture inside that wait; it has no job yet,
 * so the core's own `Busy` guard does not see it, and the clean-up would delete the file the
 * recorder is still writing into. So every start on this phone asks here first, and the disconnect
 * holds the flag from before the revoke until after the clean-up.
 *
 * It is process-wide state rather than something the graph hands out for the same reason
 * `RecorderService.state` is: the things that start a recording — a tile, a widget, a service — do
 * not share a ViewModel, and some of them have no graph at hand at all.
 */
object DisconnectGate {

    private val mutex = Mutex()

    @Volatile
    private var disconnecting = false

    /**
     * True while [hold] runs. What [app.recly.recording.RecorderService] asks; it has no screen.
     *
     * The two sides never both get past each other, and it is an ordering rather than a lock that
     * makes it so. [hold] writes the flag and *then* reads the recorder; the service's `onStart`
     * moves the recorder out of `Idle` (`session.begin()`) and *then* reads this flag, before it
     * has created anything — the first file comes later, on the coroutine it launches. Neither pair
     * has a suspension point in it, and both run on the main thread, so one of them is complete
     * before the other starts: a start that read `false` here had already published `Starting`, and
     * the disconnect that follows reads it and stops; a start that arrives any later reads `true`
     * and refuses itself. That is why the gate has no `tryLock` around the start the way the PC's
     * does — there, the start suspends between the check and the capture.
     */
    val busy: Boolean get() = disconnecting

    /** Why a recording may not start right now, or null when nothing is in the way. */
    fun startBlocker(): UiMessage? =
        if (disconnecting) UiMessage.Res(R.string.disconnect_in_progress) else null

    /**
     * Runs [block] with every recording start refused for the whole of it, one disconnect at a
     * time. The flag is cleared in a `finally`, because a gate left shut is a phone that can never
     * record again.
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
