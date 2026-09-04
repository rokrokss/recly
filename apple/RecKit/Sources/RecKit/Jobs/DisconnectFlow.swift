import Foundation

// The Google half of a disconnect needs the SDK, which ships no watchOS slice — and the watch has
// no account of its own to disconnect (ADR-002). So [DisconnectFlow] is the Mac's and the phone's,
// as `GoogleAuth` itself is; the store below it compiles everywhere the package does.
#if os(macOS) || os(iOS)
import os
import ReclyCore

/// docs/03 "로그아웃 vs 연결 해제" · docs/06: the whole of a disconnect, once, for both shells.
///
/// [DisconnectGuard] already owns the *decisions* — what to revoke, what order to write the phase
/// in, what to say at the end — and this is the thing that walks through them: the gate, the live
/// re-check, the revoke, the local clean-up and the sentence. `MenuModel` and `RecordingModel` ran
/// the same two hundred lines side by side and differed in four places, all of them here as
/// closures: which core, which sign-in, whether *this* device's recorder is running, and where the
/// message and the two persisted facts are published. [DisconnectDevice] carries the fifth — the
/// one word the sentences differ by.
///
/// `@MainActor` because both models are, and because [DisconnectGate] depends on it: the read of
/// the flag and the write that claims it have to be one turn.
@MainActor
public final class DisconnectFlow {
    private let device: DisconnectDevice
    private let logger: os.Logger
    private let core: () -> ReclyCore_?
    private let auth: () -> GoogleAuth?
    private let isRecording: () -> Bool
    private let phase: () -> DisconnectPhase
    private let debt: () -> Bool
    private let publishPhase: (DisconnectPhase) -> Void
    private let publishDebt: (Bool) -> Void
    private let publishMessage: (UiMessage) -> Void
    private let accountChanged: (String?) -> Void
    private let accountRevoked: () -> Void
    private let refresh: () async -> Void

    /// - Parameters:
    ///   - phase: the shell's published phase, which is the one this reads back before writing —
    ///     the screen and the store say the same thing, and a transition already made is not
    ///     flushed again.
    ///   - debt: the same for docs/03's "Google still lists Recly".
    ///   - accountChanged: a restore made *inside* the disconnect found a different answer than the
    ///     launch did.
    ///   - accountRevoked: the grant is gone, so whatever the shell keeps about the sign-in goes
    ///     with it.
    public init(
        device: DisconnectDevice,
        logger: os.Logger,
        core: @escaping () -> ReclyCore_?,
        auth: @escaping () -> GoogleAuth?,
        isRecording: @escaping () -> Bool,
        phase: @escaping () -> DisconnectPhase,
        debt: @escaping () -> Bool,
        publishPhase: @escaping (DisconnectPhase) -> Void,
        publishDebt: @escaping (Bool) -> Void,
        publishMessage: @escaping (UiMessage) -> Void,
        accountChanged: @escaping (String?) -> Void,
        accountRevoked: @escaping () -> Void,
        refresh: @escaping () async -> Void
    ) {
        self.device = device
        self.logger = logger
        self.core = core
        self.auth = auth
        self.isRecording = isRecording
        self.phase = phase
        self.debt = debt
        self.publishPhase = publishPhase
        self.publishDebt = publishDebt
        self.publishMessage = publishMessage
        self.accountChanged = accountChanged
        self.accountRevoked = accountRevoked
        self.refresh = refresh
    }

    /// docs/03 "연결 해제", both halves and in this order: the Google grant — which is what makes the
    /// other devices lose access too — and then the core's local clean-up (tokens, the queue, the
    /// folder cache; the workflows and the keys are this device's own and stay).
    ///
    /// docs/06: this is the one path that calls `disconnect()` rather than `signOut()`. A revoke
    /// that failed does not cancel the local half — the user asked for this device to be done with
    /// the account — but it is what the message talks about, because the grant is then still
    /// standing and only they can take it down.
    ///
    /// - Returns: whether the whole of it finished, which is what the button reports (docs/09
    ///   트렌드 2).
    public func run(alsoDeleteRecordings: Bool) async -> Bool {
        guard let core = core() else { return false }
        // Shut for the whole of it, before anything is read: the revoke below is a network round
        // trip, and a start from the menu bar, the watch, Siri or the Live Activity inside that
        // wait would give "also delete the recordings" a directory that is being written into.
        return await DisconnectGate.hold {
            // The recorder is read again here and not only when the warning opened: the dialog may
            // have stood while something started a capture, and the gate cannot refuse one that had
            // already started.
            if let blocker = DisconnectGuard.liveBlocker(recording: isRecording()) {
                publishMessage(blocker)
                logger.info("auth.disconnect.refused reason=recording")
                return false
            }
            var revokeFailure: String?
            if let auth = auth() {
                switch await revoke(with: auth) {
                case .stop: return false
                case .went(let failure): revokeFailure = failure
                }
            }
            // The account stays cleared whatever happens below: the grant is gone.
            accountRevoked()
            // Once more, now the revoke has had its wait. The gate refuses a start it is asked
            // about, but one that was already opening when the gate shut is answered here, before
            // anything of this device is deleted. The phase is written first so the retry row is on
            // screen for it.
            if let racing = DisconnectGuard.liveBlocker(recording: isRecording()) {
                // A phase that would not commit is the graver of the two: the credentials are
                // already gone and this row is what is left to finish the job with.
                publishMessage(persistPhase(.revokedCleanupOwed) ? racing : DisconnectGuard.saveFailed)
                logger.info("auth.disconnect.refused reason=recording")
                return false
            }
            // Separately from the revoke, because the two fail for different reasons and only one
            // of them is the user's to fix from here: the tokens, the queue and the folder cache
            // are still on this device, and another Disconnect is what removes them. Swallowing
            // this as `try?` reported a clean disconnect over a device that still held its
            // credentials.
            var cleanup: DisconnectResult?
            var cleanupFailure: String?
            do {
                // nil is the store refusing the phase rather than a clean-up that found nothing:
                // none of it was tried, so the tokens and the queue are both still here and there
                // is nothing to report but the store.
                guard let done = try await DisconnectGuard.owingCleanup(
                    persist: { self.persistPhase($0) },
                    cleanup: { try await core.disconnect(alsoDeleteRecordings: alsoDeleteRecordings) }
                ) else {
                    publishMessage(DisconnectGuard.saveFailed)
                    logger.info("auth.disconnect.refused reason=store")
                    return false
                }
                cleanup = done
            } catch {
                cleanupFailure = error.localizedDescription
                logger.error(
                    "auth.disconnect.cleanup.failed error=\(String(describing: error), privacy: .public)"
                )
            }
            // Never a plain "disconnected" over an owed debt — including the restart path, where
            // the revoke was skipped and there is no failure here to say the grant is up.
            publishMessage(
                DisconnectGuard.completion(
                    revokeFailure: revokeFailure,
                    cleanupFailure: cleanupFailure,
                    result: cleanup,
                    stillListed: debt(),
                    device: device
                )
            )
            logger.info(
                """
                auth.disconnect revoked=\(revokeFailure == nil, privacy: .public) \
                cleaned=\(cleanupFailure == nil, privacy: .public)
                """
            )
            await refresh()
            // A clean-up that had to keep a busy recording is still owed (the phase says so), and
            // the button must not say done over it.
            return revokeFailure == nil && cleanupFailure == nil
                && (cleanup?.busyRecordings.isEmpty ?? true)
        }
    }

    /// How far the Google half got.
    private enum RevokeOutcome {
        /// Nothing of this device may be deleted: the message is already published.
        case stop
        /// On to the clean-up. [failure] is what Google refused with, or nil when the grant went —
        /// or when there was nothing left to take away.
        case went(failure: String?)
    }

    private func revoke(with auth: GoogleAuth) async -> RevokeOutcome {
        // docs/06: the decision is made on what the SDK says *now*. A restore that failed at launch
        // — or one an aborted attempt of this very disconnect left behind — would otherwise answer
        // for a Keychain nothing has read since, and "try Disconnect again" would keep giving the
        // answer that sent the user here.
        if auth.restoration != .restored(hasCredential: true) {
            await auth.restore()
            accountChanged(auth.account)
        }
        // A retry of a disconnect whose *local* half failed has no grant left to take away — the
        // account was cleared when the revoke succeeded — so it goes straight to the clean-up rather
        // than revoking a grant this disconnect was never about.
        switch DisconnectGuard.revokeDecision(phase: phase(), restoration: auth.restoration) {
        case .revoke:
            // The phase is on disk before the revoke, not after it: both branches below clear this
            // device's credentials, and a phase written afterwards is one that can be lost with
            // them.
            let attempt = await DisconnectGuard.revoking(persist: { self.persistPhase($0) }) {
                // A revoke that failed still leaves this device disconnected — that is what the
                // dialog promised — so the identity goes either way; only the grant is still
                // standing. The debt says so, and it is written *before* `signOut` takes the
                // Keychain entry: with the token gone the retry has no way to tell a revoke that
                // happened from one that never did, and would report success over a grant Google is
                // still listing.
                await DisconnectGuard.owingDebt(
                    owe: { self.persistDebt($0) },
                    revoke: {
                        do {
                            try await auth.disconnect()
                            return nil
                        } catch {
                            return (error as? GoogleAuth.Failure)?.description
                                ?? error.localizedDescription
                        }
                    },
                    forgetTokens: { await auth.signOut() }
                )
            }
            guard case .tried(let failure) = attempt else {
                // The store would not commit what this disconnect is about to do, so it did not do
                // it: nothing was revoked, no credential was deleted and the device is exactly as
                // it was.
                publishMessage(DisconnectGuard.saveFailed)
                logger.info("auth.disconnect.refused reason=store")
                return .stop
            }
            return .went(failure: failure)

        case .unknown:
            // The sign-in could not be read at all, so neither branch is safe: the phase stays
            // owed, the Disconnect row with it, and nothing of this device is deleted over a grant
            // that may still be standing.
            guard persistPhase(.revokePending) else {
                publishMessage(DisconnectGuard.saveFailed)
                logger.info("auth.disconnect.refused reason=store")
                return .stop
            }
            publishMessage(DisconnectGuard.restoreUnknown)
            logger.info("auth.disconnect.refused reason=restore")
            return .stop

        case .skip:
            return .went(failure: nil)
        }
    }

    /// The store and the screen together: the phase outlives the process, the state draws it. The
    /// two halves of a disconnect each write the phase they are owed, so a transition that has
    /// already been made is not written again — the write is flushed, which is not free.
    ///
    /// - Returns: whether the phase is on disk. `synchronize()` answers whether the write actually
    ///   committed and that answer used to be dropped: a disconnect that cannot write down what it
    ///   is about to do must not do it, because the credentials it deletes are what the note is
    ///   there to survive. A phase that did not commit leaves the published one alone as well —
    ///   the screen says what the store says.
    @discardableResult
    public func persistPhase(_ next: DisconnectPhase) -> Bool {
        guard phase() != next else { return true }
        guard DisconnectDefaults.write(phase: next) else {
            logger.error("auth.disconnect.persist.failed key=phase")
            return false
        }
        publishPhase(next)
        return true
    }

    /// The same pairing for the debt, and the same reason for the guard and for the answer.
    @discardableResult
    public func persistDebt(_ owed: Bool) -> Bool {
        guard debt() != owed else { return true }
        guard DisconnectDefaults.write(revokeDebt: owed) else {
            logger.error("auth.disconnect.persist.failed key=debt")
            return false
        }
        publishDebt(owed)
        return true
    }

    /// docs/03: the user's own word, and the only thing that clears the debt. Recly cannot ask
    /// Google whether the grant is still listed — it has no account left to ask with — so the row
    /// stays until they say they took it down themselves.
    public func debtSettled() {
        if !persistDebt(false) { publishMessage(DisconnectGuard.saveFailed) }
    }
}
#endif

/// The two facts a disconnect leaves behind, on the device they are about.
///
/// `UserDefaults` and not the core: neither is worth syncing between machines — how far *this*
/// device's last disconnect got is a fact about it — and the store has to outlive the very
/// clean-up that empties the core. Both shells kept a verbatim copy of this, keys and migration
/// included; it is one thing because losing either of them is the same bug on both.
public enum DisconnectDefaults {
    private static let phaseKey = "disconnectPhase"
    private static let revokeDebtKey = "revokeDebt"
    /// What the build before this one wrote instead of [phaseKey]: a boolean, set only once the
    /// local clean-up had failed (docs/00-decisions.md).
    private static let legacyPendingKey = "disconnectPending"

    /// docs/03 · docs/06: how far the last disconnect got. Stored rather than kept in memory because
    /// the retry usually happens after a relaunch — a device that quietly kept the secrets and the
    /// queue of an account it no longer has is what this is here to prevent.
    ///
    /// The boolean an older install has instead of a phase is folded in ([DisconnectPhase.migrating])
    /// on the way out. The new key is written and *flushed* before the old one goes, so a process
    /// that died between the two comes back to a phase rather than to nothing; a leftover boolean
    /// under a phase this build has written is only a leftover, which is what `migrating` says.
    public static var phase: DisconnectPhase {
        let store = UserDefaults.standard
        let stored = store.string(forKey: phaseKey)
        guard store.object(forKey: legacyPendingKey) != nil else { return DisconnectPhase.of(stored) }
        let phase = DisconnectPhase.migrating(
            stored: stored,
            legacyPending: store.bool(forKey: legacyPendingKey)
        )
        store.set(phase.rawValue, forKey: phaseKey)
        // The old key is the only durable marker until the new one has reached disk: drop it only
        // after a flush that succeeded, or a death in between reads as nothing owed (Sol
        // P1-F-apple r2). A failed flush leaves both, and the next launch migrates again.
        if flush() { store.removeObject(forKey: legacyPendingKey) }
        return phase
    }

    /// - Returns: whether the write reached the store. See [flush].
    public static func write(phase: DisconnectPhase) -> Bool {
        UserDefaults.standard.set(phase.rawValue, forKey: phaseKey)
        return flush()
    }

    /// docs/03: true while Google is still listing Recly because the revoke failed.
    public static var revokeDebt: Bool { UserDefaults.standard.bool(forKey: revokeDebtKey) }

    /// - Returns: whether the write reached the store. See [flush].
    public static func write(revokeDebt owed: Bool) -> Bool {
        UserDefaults.standard.set(owed, forKey: revokeDebtKey)
        return flush()
    }

    /// The two settings whose whole point is surviving a kill: both are written *before* the
    /// credentials they are about are deleted, and `UserDefaults` otherwise hands the write to
    /// `cfprefsd` to commit whenever it likes — a process that died in that gap would come back
    /// with the account gone and the store still saying nothing was owed. `synchronize()` is
    /// documented as unnecessary for ordinary settings; these two are not ordinary.
    ///
    /// Its answer is passed on rather than dropped: a store that refused the write is one the
    /// caller must not act over.
    private static func flush() -> Bool {
        UserDefaults.standard.synchronize()
    }
}
