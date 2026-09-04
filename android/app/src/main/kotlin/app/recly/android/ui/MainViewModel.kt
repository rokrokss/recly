@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import app.recly.android.R
import app.recly.android.auth.AuthResolver
import app.recly.android.auth.AuthorizeResult
import app.recly.android.auth.RevokeResult
import app.recly.android.auth.SignInResult
import app.recly.android.core.AppGraph
import app.recly.android.core.CoreMessages
import app.recly.android.core.CoreModule
import app.recly.android.core.UiMessage
import app.recly.android.core.coreMessage
import app.recly.android.settings.AppSettings
import app.recly.android.ui.component.ProcessingState
import app.recly.android.work.WorkScheduler
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import recly.core.DisconnectResult
import recly.core.job.JobStatus
import recly.core.message.CoreMessage
import recly.core.platform.Logger

data class MainUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val email: String? = null,
    /** Where the action the user last asked for is (docs/09), for the button that asked for it. */
    val action: ProcessingState = ProcessingState.IDLE,
    /** Named, not resolved: this outlives the screen the language setting recreates (docs/07). */
    val message: UiMessage? = null,
    /** Non-null while the docs/03 disconnect warning is up, with the count it has to name. */
    val disconnect: DisconnectPrompt? = null,
    /** docs/06: how far the last disconnect got, and so whether one is still owed. */
    val disconnectPhase: DisconnectPhase = DisconnectPhase.NONE,
    /** docs/03: a revoke Google refused, so the grant is still listed and only the user can fix it. */
    val revokeDebt: Boolean = false,
)

/**
 * The whole of the M2-L1 screen: who is signed in, and whether the core can reach Drive.
 * Recording, the workflow editor and the job list are later lanes.
 */
class MainViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {

    private val settings = AppSettings(application)

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    /**
     * Held here, not in the activity: a consent screen outlives a rotation and the suspended
     * authorization has to survive with it (see [ConsentController]).
     */
    /** `internal` so instrumentation tests in this module drive the same path the AuthResolver uses. */
    internal val consent = ConsentController<PendingIntent>()
    val consentRequest: StateFlow<PendingIntent?> = consent.request

    private val resolver = object : AuthResolver {
        override suspend fun resolve(pendingIntent: PendingIntent): Intent? =
            consent.await(pendingIntent).let { if (it.ok) it.data else null }
    }

    /** True for the one activity that should show the consent screen; false for a recreated one. */
    fun consumeConsentLaunch(): Boolean = consent.consumeLaunch()

    fun onConsentResult(resultCode: Int, data: Intent?) = consent.onResult(resultCode, data)

    /**
     * Held here for the same reason as [consent], but in saved state: the add-account screen can
     * outlive the whole process, not just the activity (see [AddAccountController]).
     */
    internal val addAccount = AddAccountController(savedState)
    val addAccountRequest: StateFlow<Boolean> = addAccount.request

    fun consumeAddAccountLaunch(): Boolean = addAccount.consumeLaunch()

    /** Back from the system add-account screen — the account it may have added is worth one retry. */
    fun onAddAccountResult(activity: Activity) {
        if (addAccount.onReturned()) attemptSignIn(activity)
    }


    init {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = false,
                    email = graph().auth.account(),
                    disconnectPhase = settings.disconnectPhase.first(),
                    revokeDebt = settings.revokeDebt.first(),
                )
            }
        }
    }

    /**
     * The sign-in tap. Deliberate, so the add-account guard starts over (see [AddAccountController]).
     *
     * docs/06: refused while a disconnect is still owed. The button is already disabled for it, and
     * this is the other half — the account slot belongs to the disconnect until it has finished.
     */
    fun signIn(activity: Activity) {
        val blocker = DisconnectGuard.signInBlocker(_state.value.disconnectPhase.owed)
        if (blocker != null) {
            _state.update { it.copy(message = UiMessage.Res(blocker)) }
            return
        }
        addAccount.onUserSignIn()
        attemptSignIn(activity)
    }

    private fun attemptSignIn(activity: Activity) = work { graph ->
        when (val signedIn = graph.auth.signIn(activity)) {
            is SignInResult.Failed -> {
                _state.update { it.copy(message = reason(R.string.auth_sign_in_failed, signedIn.reason)) }
                false
            }

            SignInResult.NoAccount -> {
                addAccount.onNoAccount()
                _state.update { it.copy(message = UiMessage.Res(R.string.auth_add_account)) }
                false
            }

            is SignInResult.SignedIn -> {
                _state.update { it.copy(email = signedIn.email) }
                // Identity is not authorization (docs/06): the Drive grant is a separate consent.
                authorize(graph, activity)
            }
        }
    }

    /**
     * The way back for an identity that is signed in but has no Drive grant — a revoked consent, or
     * a ViewModel lost to process death mid-consent. Without it the user only has the silent path,
     * which is exactly the one that cannot recover.
     */
    fun reauthorizeDrive(activity: Activity) = work { graph -> authorize(graph, activity) }

    /** @return whether the grant is in hand — the message says which way it went either way. */
    private suspend fun authorize(graph: AppGraph, activity: Activity): Boolean {
        val authorized = graph.auth.authorizeDrive(activity, resolver)
        val message = when (authorized) {
            is AuthorizeResult.Granted -> {
                val unparked = unparkNeedsAuth(graph)
                if (unparked > 0) {
                    UiMessage.Res(R.string.auth_drive_granted_unparked, listOf(unparked))
                } else {
                    UiMessage.Res(R.string.auth_drive_granted)
                }
            }

            AuthorizeResult.NeedsConsent -> UiMessage.Res(CoreMessages.resourceOf(CoreMessage.DRIVE_CONSENT_REQUIRED))

            is AuthorizeResult.Failed -> reason(R.string.auth_drive_failed, authorized.reason)
        }
        _state.update { it.copy(message = message) }
        return authorized is AuthorizeResult.Granted
    }

    /**
     * docs/10 "잡 상태 머신": `NEEDS_AUTH ──sign in──► PENDING`. A job parked for want of a token is
     * the one thing signing in is supposed to fix, and nothing else in the app would ever unpark it.
     */
    private suspend fun unparkNeedsAuth(graph: AppGraph): Int {
        val parked = graph.core.jobs.observe().first().filter { it.status == JobStatus.NEEDS_AUTH }
        val unparked = parked.count { graph.core.jobs.retry(it.id) }
        if (unparked > 0) WorkScheduler(getApplication()).onJobsDue()
        return unparked
    }

    fun signOut() = work { graph ->
        // The account slot belongs to a disconnect until it has finished: a plain sign-out after
        // REVOKE_PENDING would delete the account the retry needs to tell "revoke again" from
        // "already revoked", and the grant would stand with no debt recorded (Sol P1-F r1).
        DisconnectGuard.signInBlocker(_state.value.disconnectPhase.owed)?.let { blocker ->
            _state.update { it.copy(message = UiMessage.Res(blocker)) }
            return@work false
        }
        graph.auth.signOut()
        _state.update { it.copy(email = null, message = null) }
        true
    }

    /**
     * Opens the docs/03 warning. The count is read first because the dialog has to state it: a
     * user who is about to lose the queue deserves to know what is still only on this phone.
     */
    fun askToDisconnect() {
        viewModelScope.launch {
            val prompt = DisconnectPrompt(
                unuploaded = Retention.unuploadedRecordings(graph().core),
                recording = isRecording(),
            )
            _state.update { it.copy(disconnect = prompt) }
        }
    }

    fun cancelDisconnect() = _state.update { it.copy(disconnect = null) }

    /**
     * The confirm, and the one thing it does before the disconnect: it reads again what the dialog
     * promised. The Mac's `MenuModel.disconnect` is the same two halves.
     */
    fun disconnect(alsoDeleteRecordings: Boolean) {
        // The second half of a double-tap must not catch the re-presented warning below and confirm
        // one nobody has read: from the first tap until its re-read decides, every further tap is a
        // no-op.
        val shown = _state.value.disconnect ?: return
        if (disconnectChecking) return
        disconnectChecking = true
        viewModelScope.launch {
            // The dialog stands for as long as the user leaves it there, and a recording started
            // from the tile, the widget, the shortcut or the watch can finish inside that — the
            // phone then holds audio the warning never counted. What it promised is read again
            // before it is acted on; a warning it never showed re-asks instead of destroying
            // quietly.
            val fresh = DisconnectPrompt(
                unuploaded = Retention.unuploadedRecordings(graph().core),
                recording = isRecording(),
            )
            // Down before the dialog changes either way: what a further tap then finds is the
            // re-presented warning, which is a question it has not answered yet, or no dialog
            // at all.
            disconnectChecking = false
            if (fresh.warnsMore(shown)) {
                _state.update { it.copy(disconnect = fresh) }
                return@launch
            }
            performDisconnect(alsoDeleteRecordings)
        }
    }

    /** True from a confirm until its re-read decides — see [disconnect]. */
    private var disconnectChecking = false

    /**
     * docs/03 "연결 해제", both halves and in this order: the Google grant, which is what makes the
     * other devices lose access too, and then the core's local clean-up (tokens, the queue, the
     * folder cache). A revoke that failed does not cancel the local
     * half — the user asked for this device to be done with the account — but it is what the
     * message talks about, because the grant is then still standing and only they can take it down.
     */
    private fun performDisconnect(alsoDeleteRecordings: Boolean) = work { graph ->
        _state.update { it.copy(disconnect = null) }
        try {
            // Shut for the whole of it, before anything is read: the revoke below is a network
            // round trip, and a tile, a widget or the launcher shortcut starting a capture inside
            // that wait would give "also delete the recordings" a directory that is written into.
            DisconnectGate.hold { runDisconnect(graph, alsoDeleteRecordings) }
        } catch (e: PersistFailed) {
            // The store would not commit what this disconnect was about to do, so it did not do it:
            // the phase and the debt are written *before* the account they are about is deleted, and
            // a write that threw came back out of the guard before the revoke, the sign-out or the
            // clean-up was reached. Nothing was revoked, nothing was deleted and the phone is as it
            // was — which is all there is to say about it.
            graph.core.deps.logger.log(Logger.Level.ERROR, "auth.disconnect.store.failed", error = e)
            _state.update { it.copy(message = DisconnectGuard.saveFailed) }
            false
        }
    }

    /**
     * The disconnect itself, out of line so the store's refusal has somewhere to be caught: every
     * other way out of this is a sentence for the user, and that one is a sentence for the user
     * too — it just cannot be written where the write that failed is. The PC twin is the same
     * `ShellModel.runDisconnect`.
     */
    private suspend fun runDisconnect(graph: AppGraph, alsoDeleteRecordings: Boolean): Boolean {
        // The recorder is read again here and not only when the warning opened: the dialog may
        // have stood while something started a capture, and the gate cannot refuse one that had
        // already started.
        val live = DisconnectGuard.liveBlocker(isRecording())
        if (live != null) {
            _state.update { it.copy(message = live) }
            return false
        }
        var revoked: RevokeResult? = null
        // A retry of a disconnect whose *local* half failed has no grant left to take away —
        // the account was cleared when the revoke succeeded — so it goes straight to the
        // clean-up rather than revoking a grant this disconnect was never about.
        if (DisconnectGuard.revokes(_state.value.disconnectPhase, _state.value.email != null)) {
            // The phase is on disk before the revoke, not after it: both of the branches below
            // clear this phone's account (`revokeAccess` signs out on its way through, and the
            // failure branch signs out too), and a phase written afterwards is one that can be
            // lost with it.
            revoked = DisconnectGuard.revoking(::persistPhase) {
                // A revoke that failed still leaves this device disconnected — that is what the
                // dialog promised — so the identity goes either way; only the grant is still
                // standing. The debt says so, and it is written *before* `signOut` takes the
                // account: with the account gone the retry has no way to tell a revoke that
                // happened from one that never did, and would report success over a grant
                // Google is still listing.
                DisconnectGuard.owingDebt(
                    owe = ::persistRevokeDebt,
                    revoke = { graph.auth.revokeAccess() },
                    forgetAccount = { graph.auth.signOut() },
                )
            }
        }
        // The account stays cleared whatever happens below: the grant is gone.
        _state.update { it.copy(email = null) }
        // Once more, now the revoke has had its wait. The gate refuses a start it is asked
        // about, and `RecorderService` asks — but a start that was already in flight when the
        // gate shut is answered here, before anything of this phone is deleted. The phase is
        // written first so the retry row is on screen for it: the grant is already gone.
        val racing = DisconnectGuard.liveBlocker(isRecording())
        if (racing != null) {
            persistPhase(DisconnectPhase.REVOKED_CLEANUP_OWED)
            _state.update { it.copy(message = racing) }
            return false
        }
        // Caught separately from the revoke, because the two fail for different reasons and only
        // one of them is the user's to fix from here: the tokens, the queue and the folder cache
        // are still on this phone, and another Disconnect is what removes them. Letting it out of
        // `work` reported nothing at all over a phone that was still holding a live token.
        var result: DisconnectResult? = null
        var cleanupFailure: String? = null
        try {
            result = DisconnectGuard.owingCleanup(::persistPhase) {
                graph.core.disconnect(alsoDeleteRecordings)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: PersistFailed) {
            // The store refusing the phase, not a clean-up that failed: none of it was tried, so
            // the tokens and the queue are all still here and the sentence for that is the store's
            // rather than "cleaning up this device failed".
            throw e
        } catch (e: Exception) {
            cleanupFailure = e.message ?: e::class.java.simpleName
            graph.core.deps.logger.log(Logger.Level.ERROR, "auth.disconnect.cleanup.failed", error = e)
        }
        // Never a plain "disconnected" over an owed debt — including the restart path, where
        // the revoke was skipped and there is no [RevokeResult] here to say the grant is up.
        val completion = DisconnectGuard.completion(
            revoked = revoked,
            cleanupFailure = cleanupFailure,
            result = result,
            stillListed = _state.value.revokeDebt,
        )
        _state.update { it.copy(message = completion) }
        // A clean-up that had to keep a busy recording is still owed (the phase says so), and
        // the button must not say done over it.
        return revoked !is RevokeResult.Failed && cleanupFailure == null && result?.busyRecordings.isNullOrEmpty()
    }

    /**
     * The store and the screen together: the phase outlives the process, the state draws it. The
     * two halves of a disconnect each write the phase they are owed, so a transition that has
     * already been made is not written again — the store's write is awaited, which is not free.
     *
     * A DataStore that would not take the write throws [PersistFailed] rather than carrying on: the
     * phase this disconnect is about to act on has to be on disk *before* the account it is about is
     * deleted, and the guards are written so that the throw is out of them before anything is
     * revoked. The state is left alone with the store — the two say the same thing, which is what
     * the retry reads.
     */
    private suspend fun persistPhase(phase: DisconnectPhase) {
        if (_state.value.disconnectPhase == phase) return
        try {
            settings.setDisconnectPhase(phase)
        } catch (e: IOException) {
            throw PersistFailed(e)
        }
        _state.update { it.copy(disconnectPhase = phase) }
    }

    /** The same pairing for the debt, and the same reason for the guard: the write is awaited. */
    private suspend fun persistRevokeDebt(owed: Boolean) {
        if (_state.value.revokeDebt == owed) return
        try {
            settings.setRevokeDebt(owed)
        } catch (e: IOException) {
            throw PersistFailed(e)
        }
        _state.update { it.copy(revokeDebt = owed) }
    }

    /**
     * The settings store refusing one of the two writes a disconnect cannot go on without. A type of
     * its own so [disconnect] can tell it from everything else that can fail in there — a revoke
     * Google refused and a clean-up that threw are both sentences about this phone, and this one is
     * a sentence about the store, over a phone nothing was done to.
     */
    private class PersistFailed(cause: IOException) : Exception(cause)

    /**
     * docs/03: the user's own word, and the only thing that clears the debt. Recly cannot ask
     * Google whether the grant is still listed — it has no account left to ask with — so the row
     * stays until they say they took it down themselves. A store that would not take the answer says
     * so rather than dropping it: the row is back on the next launch either way, and a button that
     * did nothing and said nothing would only be pressed again.
     */
    fun revokeDebtSettled() {
        viewModelScope.launch {
            try {
                persistRevokeDebt(false)
            } catch (e: PersistFailed) {
                _state.update { it.copy(message = DisconnectGuard.saveFailed) }
            }
        }
    }

    /** docs/12: the recorder is a live service, not a queue row — the core's Busy guard misses it. */
    private fun isRecording(): Boolean = RecorderService.state.value != RecorderState.Idle

    /**
     * [processing], plus the two things only this screen's actions carry: they are one at a time —
     * a second tap while one runs does nothing — and starting one clears what the last one said.
     *
     * @param block whether the action did what the user asked, which is what the button shows.
     */
    private fun work(block: suspend (AppGraph) -> Boolean) {
        if (_state.value.busy) return
        viewModelScope.processing(
            phase = { phase ->
                val running = phase == ProcessingState.PROCESSING
                _state.update {
                    it.copy(
                        busy = running,
                        action = phase,
                        message = if (running) null else it.message,
                    )
                }
            },
        ) { block(graph()) }
    }

    /**
     * A reason is a [CoreMessage] code when the shell had one, and a diagnostic otherwise. Either
     * way it is nested into the sentence as a message rather than resolved into it — this state
     * outlives the screen the language setting recreates (docs/07 rule 3).
     */
    private fun reason(id: Int, reason: String): UiMessage =
        UiMessage.Res(id, listOf(coreMessage(reason)))

    private suspend fun graph(): AppGraph = CoreModule.get(getApplication())
}
