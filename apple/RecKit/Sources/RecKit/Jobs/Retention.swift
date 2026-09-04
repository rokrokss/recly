import Foundation
import ReclyCore

/// docs/03 "보관 · 삭제": what the delete dialog and the disconnect warning have to say *first* —
/// how much audio exists only on this device.
///
/// A file on disk used to be the whole of that answer — ADR-017 purged the parts as soon as every
/// job of the recording was DONE with its uploads, so anything left was something Drive had not got.
/// The seven-day window ended that: a part stays here for a week *after* Drive has it, and counting
/// it would tell the user their recording is about to be lost while Drive is holding it. So the core
/// is asked instead ([ReclyCore_.uploaded]), and the disk is only read for the other half of the
/// sentence — which parts are still here to be deleted at all. The Android and Windows shells read
/// it the same way.
public enum Retention {

    /// How many of one recording's parts are still only here — the count the delete dialog leads
    /// with, because that is the part of the deletion nothing anywhere else can give back.
    public static func unuploadedParts(core: ReclyCore_, recordingId: String) async -> Int {
        guard let record = try? await core.recordings.get(id: recordingId) else { return 0 }
        return unuploaded(
            partsOnDisk: onDisk(core: core, record: record).count,
            uploaded: (try? await core.uploaded(recordingId: recordingId))?.boolValue == true
        )
    }

    /// How many recordings still have audio here, over the newest [limit] of them — the count the
    /// disconnect warning names, because those are the ones that stay behind.
    ///
    /// Asked once for the whole list rather than per recording: the answer is one query either way,
    /// and a warning is not worth [scan] round trips. A query that failed leaves the set empty,
    /// which counts every recording — the cautious direction for a sentence about what is lost.
    public static func unuploadedRecordings(core: ReclyCore_, limit: Int32 = scan) async -> Int {
        let records = (try? await core.recordings.list(limit: limit)) ?? []
        let uploaded = (try? await core.uploadedRecordings()) ?? []
        return records.count {
            staysHere(
                partsOnDisk: onDisk(core: core, record: $0).count,
                uploaded: uploaded.contains($0.id)
            )
        }
    }

    /// Whether this recording's audio would go with the device: files of its own still here, and no
    /// job that has put every part of it into Drive.
    public static func staysHere(partsOnDisk: Int, uploaded: Bool) -> Bool {
        partsOnDisk > 0 && !uploaded
    }

    /// The same question with the number attached, which is what the delete dialog says: nothing to
    /// name once Drive holds them all, however many files the seven-day window has left here.
    public static func unuploaded(partsOnDisk: Int, uploaded: Bool) -> Int {
        staysHere(partsOnDisk: partsOnDisk, uploaded: uploaded) ? partsOnDisk : 0
    }

    private static func onDisk(core: ReclyCore_, record: RecordingRecord) -> [Part_] {
        record.meta.parts.filter { part in
            (try? core.deps.fileSystem.exists(path: record.dir.resolve(child: part.file, normalize: false))) == true
        }
    }

    /// Enough of the list to count what Drive has not got — the same depth Android scans.
    public static let scan: Int32 = 100
}

private extension Array {
    func count(_ isIncluded: (Element) -> Bool) -> Int {
        filter(isIncluded).count
    }
}

/// docs/03 "앱에서 지우기": what the delete dialog has to know before it can ask. [unuploaded] is how
/// many parts are still only on this device, which the dialog says first — that is the part of the
/// deletion nothing anywhere else can give back.
public struct DeleteRequest: Identifiable, Equatable, Sendable {
    public let recordingId: String
    public let title: String
    public let unuploaded: Int
    /// docs/03: whether another device recorded this and this one only adopted the Drive folder.
    /// There is no local half to keep, so the dialog has no question to ask — it says what the
    /// deletion reaches instead.
    public let remote: Bool

    public var id: String { recordingId }

    public init(recordingId: String, title: String, unuploaded: Int, remote: Bool = false) {
        self.recordingId = recordingId
        self.title = title
        self.unuploaded = unuploaded
        self.remote = remote
    }
}

/// docs/03 "앱에서 지우기": what became of one recording's deletion, as the shells have to answer for
/// it. `core.recordings.delete` is a Kotlin sealed result and both shells walked the same `onEnum`
/// over it; what they do *with* each case is theirs, because the Mac has a transcript window open on
/// the recording and the two put their sentences in different places.
public enum RecordingDeletion {
    public enum Outcome: Equatable, Sendable {
        /// docs/03: a recording being written to or uploaded right now is not one to delete, and
        /// the core refuses it.
        case busy
        /// Gone from here. [driveError] is what Drive refused with, when the user asked for the
        /// folder too and Drive would not — the local deletion is not undone by it.
        case deleted(driveError: String?)
        case notFound
        /// The core is not open, or the call itself threw: nothing was deleted and there is nothing
        /// to say about Drive.
        case unavailable
    }

    public static func delete(
        core: ReclyCore_,
        recordingId: String,
        deleteDrive: Bool
    ) async -> Outcome {
        guard let result = try? await core.recordings.delete(
            recordingId: recordingId,
            deleteDrive: deleteDrive
        ) else { return .unavailable }
        switch onEnum(of: result) {
        case .busy: return .busy
        case .deleted(let deleted): return .deleted(driveError: deleted.driveError)
        case .notFound: return .notFound
        }
    }
}

/// docs/03 "연결 해제": the warning, and the count it has to name — the recordings Drive has not got
/// yet, which stay on this device unless the user asks for them to go too.
public struct DisconnectPrompt: Identifiable, Equatable, Sendable {
    public let unuploaded: Int
    /// Whether the recorder is doing anything at all — starting, recording or finishing.
    public let recording: Bool

    public var id: Int { unuploaded }

    public init(unuploaded: Int, recording: Bool = false) {
        self.unuploaded = unuploaded
        self.recording = recording
    }

    /// Whether the disconnect may go ahead.
    ///
    /// A capture that is running has no job yet — the job is made at the stop — so the core's own
    /// `Busy` guard, which looks at the queue, does not cover it: "also delete the recordings"
    /// would take the directory out from under the recorder that is still writing into it. And a
    /// disconnect that stopped the recording for the user would be answering a question nobody
    /// asked, so the answer is to say what is in the way and let them stop it themselves.
    public var canConfirm: Bool { !recording }

    /// The line that says what is in the way, or nil when nothing is.
    public var blocker: String? {
        recording ? RecKitStrings.localized("Stop the recording first") : nil
    }

    /// True when this freshly read state carries a warning the dialog the user confirmed never
    /// showed — a recording that finished since [shown] was built and grew the count. The confirm
    /// re-presents with this instead of acting on a promise the dialog did not make. `recording` is
    /// not compared: it has its own live guards ([canConfirm] on the dialog, and the flow's re-read
    /// at run time).
    public func warnsMore(than shown: DisconnectPrompt) -> Bool {
        unuploaded > shown.unuploaded
    }

    /// The state read again at the moment the confirm is acted on, and the answer to the only
    /// question that leaves the shell: whether to re-present instead of going ahead.
    ///
    /// The dialog may have stood — or survived a dismissed popover — while a recording finished, so
    /// what it promised is read once more before anything is destroyed over it. Nil is "go ahead":
    /// the fresh state warns no more than [shown] did, or there is no core open to read at all —
    /// which is a disconnect that will refuse itself further down anyway.
    ///
    /// Both shells ask this, with the one thing they differ by as [recording]: `MenuModel` reads the
    /// Mac's recorder, `RecordingModel` the phone's.
    public static func rewarning(
        core: ReclyCore_?,
        recording: Bool,
        shown: DisconnectPrompt
    ) async -> DisconnectPrompt? {
        guard let core else { return nil }
        let fresh = DisconnectPrompt(
            unuploaded: await Retention.unuploadedRecordings(core: core),
            recording: recording
        )
        return fresh.warnsMore(than: shown) ? fresh : nil
    }
}

/// docs/03 "연결 해제" · docs/06: the decisions a disconnect makes at the moment it *runs*, which is
/// not the moment [DisconnectPrompt] was built. A dialog is on screen for as long as the user leaves
/// it there, and a retry may be a whole launch later — so the two things that could make a
/// disconnect do the wrong thing are decided here and asked again by the shell that is about to act.
///
/// The Mac and the phone have one copy of these between them because they are the same decisions:
/// `MenuModel` and `RecordingModel` differ only in which recorder they read.
public enum DisconnectGuard {

    /// Whether this disconnect still has a Google grant of its own to take away.
    ///
    /// The regression: a disconnect whose *local* half failed leaves
    /// [DisconnectPhase.revokedCleanupOwed] on disk and the account cleared — the grant is already
    /// gone. If the user then signed in again, the retry saw an account and revoked it, taking away
    /// a grant this disconnect was never about. So a retry of that phase skips the revoke entirely
    /// and does the clean-up it actually owes, and [signInBlocker] is the other end of the same
    /// rule: there is no second account to be signed in with while a disconnect is owed.
    ///
    /// [DisconnectPhase.revokePending] is the other way round — the phase was written *before* the
    /// revoke, so the process may have died with the grant still standing and the SDK's refresh
    /// token still in the Keychain. A token that is still here is a revoke that never happened, so
    /// the retry tries it again; one that is gone means the revoke got as far as clearing it, and
    /// the retry goes on to the clean-up.
    ///
    /// [signedIn] is "this device holds a credential" and never "there is an account email":
    /// Google's profile is optional, so a valid user may carry none, and reading the email as the
    /// sign-in would send such a user's disconnect straight past the revoke. See
    /// [revokeDecision], which is what the shells ask.
    public static func revokes(phase: DisconnectPhase, signedIn: Bool) -> Bool {
        signedIn && phase != .revokedCleanupOwed
    }

    /// What the Google half of a disconnect does, decided at the moment it runs.
    public enum RevokeStep: Equatable, Sendable {
        /// This device holds a grant of its own: take it away.
        case revoke
        /// There is nothing left to revoke — on to the local clean-up.
        case skip
        /// It is not known whether there is: the restore failed, so the sign-in could not be read
        /// at all. Neither branch is safe — revoking may take away an account this disconnect was
        /// never about, skipping may report success over a grant Google is still listing — so the
        /// disconnect stops, the phase stays owed and the user is asked to try again.
        case unknown
    }

    /// [revokes] with the third answer the boolean had no room for.
    ///
    /// The regression: the shells derived "signed in" from `account != nil`, and both a restore
    /// that failed and a user without an email land there as nil. A disconnect then went on to
    /// delete every key on the device while the grant it was supposed to revoke was never touched
    /// and nothing on screen said so.
    ///
    /// [DisconnectPhase.revokedCleanupOwed] is the one phase a failed restore does not stop: the
    /// grant is known to be gone — this disconnect took it — and what is owed is the local
    /// clean-up, which needs no sign-in at all.
    public static func revokeDecision(phase: DisconnectPhase, restoration: GoogleRestoration) -> RevokeStep {
        switch restoration {
        case .restored(let hasCredential):
            return revokes(phase: phase, signedIn: hasCredential) ? .revoke : .skip
        case .none:
            return revokes(phase: phase, signedIn: false) ? .revoke : .skip
        case .failed:
            return phase == .revokedCleanupOwed ? .skip : .unknown
        }
    }

    /// docs/06: what a disconnect says when it could not read the sign-in it has to decide by. The
    /// phase is left owed, so the Disconnect row stays on screen and this is a sentence about the
    /// button the user is already looking at.
    public static let restoreUnknown = UiMessage.key(
        "Could not check the Google sign-in — try Disconnect again"
    )

    /// docs/03 · docs/06: what a disconnect says when the store would not commit the phase it is
    /// about to act on. Nothing was revoked and no credential was deleted — a disconnect that
    /// cannot write down what it is doing must not do it, because the note is the only thing that
    /// survives the credentials it deletes.
    public static let saveFailed = UiMessage.key("Could not save the disconnect state — try again")

    /// Why signing in — and signing out, which would take the account the retry reads — is refused
    /// while a disconnect is still owed, or nil when it is not.
    public static func signInBlocker(pending: Bool) -> UiMessage? {
        pending ? .key("Finish disconnecting first") : nil
    }

    /// What to say instead of disconnecting when a capture is live *now*, or nil when none is.
    ///
    /// [DisconnectPrompt.recording] is read when the warning opens, and a recording can start while
    /// it stands — from the watch, from Siri, from the Live Activity, from the menu bar. Confirming
    /// then would run "also delete the recordings" over the directory the recorder is still writing
    /// into, so the recorder is read once more here and the answer is docs/12's: say what is in the
    /// way, never stop it for them.
    public static func liveBlocker(recording: Bool) -> UiMessage? {
        recording ? .key("Stop the recording first") : nil
    }

    /// docs/03: the row a failed revoke leaves behind. The grant is still standing and it is
    /// Google's page — not this app — that takes it down, so the row outlives the disconnect, the
    /// phase and even the signed-in state.
    public static let stillListed = UiMessage.key(
        "Google still lists Recly — remove it at myaccount.google.com/permissions"
    )

    /// The only thing that closes it: the user's own word. Recly cannot ask Google whether the
    /// grant is still listed — it has no account left to ask with.
    public static let debtSettled = UiMessage.key("I removed it")

    /// The Google half of a disconnect with the phase written around it: pending *before* the
    /// revoke is tried, owed only once it has come back.
    ///
    /// The order is the whole point, and it is the same one [owingCleanup] keeps for the local
    /// half. Writing nothing before the revoke meant every credential this device holds could be
    /// deleted — `GoogleAuth.disconnect` clears the account on its way out and the failure branch
    /// signs out too — while the store still said [DisconnectPhase.none]. A process killed in that
    /// window came back signed out, with the tokens and the queue both still here and nothing on
    /// screen offering to finish the job. [DisconnectPhase.revokePending] is what is on
    /// disk for the whole of it.
    ///
    /// A phase that could not be *flushed* stops the whole thing before anything is revoked.
    /// `UserDefaults.synchronize()` is what commits the write, and its answer used to be dropped on
    /// the floor: a store that refused left the credentials deleted anyway and nothing on disk
    /// saying a disconnect had ever started. So a write that did not commit is
    /// [RevokeAttempt.notSaved] — nothing run, nothing deleted, the device exactly as it was.
    public static func revoking(
        persist: (DisconnectPhase) async -> Bool,
        revoke: () async -> RevokeAttempt
    ) async -> RevokeAttempt {
        guard await persist(.revokePending) else { return .notSaved }
        let attempt = await revoke()
        // Only a revoke that was actually tried has cleared this device's credentials, and only
        // then is the clean-up what is owed. A [RevokeAttempt.notSaved] from inside left the token
        // where it was, so the phase stays pending and the retry revokes again.
        guard case .tried = attempt else { return attempt }
        // The result is deliberately not propagated: by here the credentials are gone either way,
        // and refusing to go on would leave the device with nothing cleaned up. A phase left at
        // `REVOKE_PENDING` with no token is read as "the revoke happened" by [revokes] anyway.
        _ = await persist(.revokedCleanupOwed)
        return attempt
    }

    /// How the Google half of a disconnect ended.
    public enum RevokeAttempt: Equatable, Sendable {
        /// A phase or a debt that had to be on disk *first* would not flush, so nothing that
        /// deletes a credential was run: no revoke, no sign-out, nothing to tell the user beyond
        /// [saveFailed] and nothing to undo.
        case notSaved
        /// The revoke ran. [failure] is nil when the grant went, and the words Google refused with
        /// when it did not.
        case tried(failure: String?)
    }

    /// The revoke itself, with the debt written around it: a failure is on disk *before* the
    /// credentials it is about are deleted.
    ///
    /// The order is the whole point, as it is in [revoking] and [owingCleanup], and the regression
    /// is one the phase alone could not catch. `GoogleAuth.signOut` on the failure branch clears
    /// the SDK's Keychain entry; a process killed between that and the phase write came back with
    /// [DisconnectPhase.revokePending] and no token, so [revokes] — which reads the sign-in state
    /// to decide whether the revoke ever happened — read the missing token as "it did", let the
    /// clean-up run on to [DisconnectPhase.none], and the flow reported success over a Google grant
    /// that was still standing. So the failure is written first and survives the token it is about;
    /// nothing but the user's own word clears it.
    ///
    /// A revoke that succeeded says nothing about a debt already standing either: the app keeps no
    /// account identity once it is done, so the grant just taken away may belong to another account
    /// than the one still listing Recly.
    ///
    /// A debt that could not be flushed stops the sign-out for the same reason [revoking] stops the
    /// revoke: `signOut` deletes the token the retry reads, and a debt that is not on disk is one
    /// the retry cannot know about. The grant is left standing, the token is left where it is and
    /// the phase stays pending — a retry then revokes again, which is the state the user is in.
    ///
    /// - Parameter revoke: the revoke, with its failure in words — nil when the grant went.
    /// - Returns: the same, for the sentence the disconnect ends on.
    public static func owingDebt(
        owe: (Bool) async -> Bool,
        revoke: () async -> String?,
        forgetTokens: () async -> Void
    ) async -> RevokeAttempt {
        guard let failure = await revoke() else { return .tried(failure: nil) }
        guard await owe(true) else { return .notSaved }
        await forgetTokens()
        return .tried(failure: failure)
    }

    /// What the Disconnect row says once it is over, in the order the user can do something about:
    /// the two failures first, then the recordings a running job would not let go of, then the
    /// grant Google is still listing, and only then the plain success.
    ///
    /// [stillListed] is why this is a function rather than an `if` in the shell: a disconnect that
    /// *skipped* the revoke — the restart path, where [DisconnectPhase.revokePending] and a cleared
    /// sign-in send it straight to the clean-up — has no revoke failure to read, and saying
    /// "Disconnected" over an owed debt is the bug this whole guard is about.
    public static func completion(
        revokeFailure: String?,
        cleanupFailure: String?,
        result: DisconnectResult?,
        stillListed: Bool,
        device: DisconnectDevice
    ) -> UiMessage {
        if let revokeFailure {
            return .key(
                "Google would not take the access away (%@) — do it in your Google account permissions",
                args: [.verbatim(revokeFailure)]
            )
        }
        if let cleanupFailure {
            return .key(
                "Google access was revoked, but cleaning up this device failed: %@ — try Disconnect again",
                args: [.verbatim(cleanupFailure)]
            )
        }
        // The settled Windows·Android sentence, word for word (`disconnect.busy` ·
        // `disconnect_busy`): a clean-up that had to keep a running recording did not finish, the
        // phase says as much, and "Disconnected" over it would be the same lie the debt is about.
        // Only the placeholder differs — Apple formats a [UiMessage] argument as a string.
        if let busy = result?.busyRecordings, !busy.isEmpty {
            return .key(
                "Google access was revoked, but %@ recording(s) were still running and were kept — disconnect again once they have finished.",
                args: [.verbatim("\(busy.count)")]
            )
        }
        if stillListed { return Self.stillListed }
        if let deleted = result?.deletedRecordings, deleted > 0 {
            return .key(device.deleted, args: [.verbatim("\(deleted)")])
        }
        return .key(device.done)
    }

    /// The local half of a disconnect with the phase written around it: owed *before* it is tried,
    /// cleared only once it has actually finished.
    ///
    /// It is a function of its own so the order can be tested without a shell, and the order is the
    /// whole point. Writing the phase after `core.disconnect` returned meant a process that died
    /// inside the clean-up — or a clean-up that never returned at all — came back with the account
    /// cleared, every key and the whole queue still on the device, and nothing on screen saying so.
    /// A throw is left to the caller for the same reason: it leaves the phase owed.
    ///
    /// Returning is not the same as having finished, either: [DisconnectResult.busyRecordings] are
    /// the ones a `RUNNING` job would not let go of, and the core deliberately kept them *and*
    /// their queue rows for it. That clean-up is still owed — the Disconnect row is what runs the
    /// rest of it once the job has finished — so the phase is only cleared when the result has
    /// none. Without "also delete the recordings" the list is always empty and the phase clears as
    /// it always did.
    /// - Returns: nil when the phase would not flush, in which case the clean-up was never run —
    ///   the tokens and the queue are both still here, which is the state the phase that could
    ///   not be written was going to be the record of.
    public static func owingCleanup(
        persist: (DisconnectPhase) async -> Bool,
        cleanup: () async throws -> DisconnectResult
    ) async rethrows -> DisconnectResult? {
        guard await persist(.revokedCleanupOwed) else { return nil }
        let result = try await cleanup()
        // A phase that would not clear leaves the clean-up owed over one that has already run: the
        // Disconnect row stays, and running it again over a device that is already clean is what
        // the retry does anyway.
        if result.busyRecordings.isEmpty { _ = await persist(.none) }
        return result
    }
}

/// The one word the sentences of a disconnect — and of the two dialogs around it — differ by. RecKit
/// is shared and these are not: a Mac that said "deleted from this phone" would be wrong in a way no
/// translation fixes.
public enum DisconnectDevice: Sendable {
    case phone
    case mac

    var deleted: String {
        switch self {
        case .phone: return "Disconnected — %@ recording(s) deleted from this phone."
        case .mac: return "Disconnected — %@ recording(s) deleted from this Mac."
        }
    }

    var done: String {
        switch self {
        case .phone: return "Disconnected. The recordings on this phone were left where they are."
        case .mac: return "Disconnected. The recordings on this Mac were left where they are."
        }
    }

    /// docs/03 "앱에서 지우기": the answer that leaves the Drive folder alone.
    var deleteHereOnly: String {
        switch self {
        case .phone: return "Delete on this phone only"
        case .mac: return "Delete on this Mac only"
        }
    }

    /// docs/03 "로그아웃 vs 연결 해제": the first and gravest of them, and the one that names the
    /// thing to do instead.
    var everyDeviceLosesAccess: String {
        switch self {
        case .phone:
            return "Every device signed in with this account loses access, not only this one. To leave just this phone, sign out instead."
        case .mac:
            return "Every device signed in with this account loses access, not only this one. To leave just this Mac, sign out instead."
        }
    }

    /// What the disconnect leaves behind, with the count the warning has to name.
    var unuploadedStay: String {
        switch self {
        case .phone: return "%@ recording(s) have not reached Drive yet and stay on this phone."
        case .mac: return "%@ recording(s) have not reached Drive yet and stay on this Mac."
        }
    }

    /// docs/03: the queue is account-derived and goes; the workflows and the keys are this
    /// device's own configuration and stay, which is the half a user is most likely to fear.
    var queueWiped: String {
        switch self {
        case .phone: return "The upload queue on this phone is wiped. Workflows and keys stay."
        case .mac: return "The upload queue on this Mac is wiped. Workflows and keys stay."
        }
    }

    /// The one irreversible half, and so the one that is a separate, unchecked answer.
    var alsoDeleteRecordings: String {
        switch self {
        case .phone: return "Also delete the recordings on this phone"
        case .mac: return "Also delete the recordings on this Mac"
        }
    }
}

/// docs/03 "연결 해제" · docs/06: how far the last disconnect got. It is persisted because the retry
/// may be a whole launch later — the account is already cleared by then, so this is the only thing
/// that keeps the Disconnect row on screen and keeps a second account out of the slot until the
/// disconnect has finished both of its halves.
public enum DisconnectPhase: String, CaseIterable, Sendable {

    /// Nothing owed: no disconnect has run, or the last one finished.
    case none = "NONE"

    /// The revoke has been asked for and has not come back. Written before it, because the revoke
    /// is what clears this device's credentials and a phase written after it is a phase that can be
    /// lost with them. On the next launch it is not known whether the grant went, so the retry asks
    /// the SDK: a sign-in still restored is revoked again, one that is gone goes to the clean-up
    /// ([DisconnectGuard.revokes]).
    case revokePending = "REVOKE_PENDING"

    /// The Google grant is gone and the credentials with it; the local clean-up has not succeeded.
    case revokedCleanupOwed = "REVOKED_CLEANUP_OWED"

    /// The one question the rest of the app asks of it: is a disconnect still unfinished?
    public var owed: Bool { self != .none }

    /// A name this build does not know is not a phase it owes; so is a store that is empty.
    public static func of(_ name: String?) -> DisconnectPhase {
        guard let name, let phase = DisconnectPhase(rawValue: name) else { return .none }
        return phase
    }

    /// docs/00-decisions.md: what an install of the build *before* this one has instead of a phase —
    /// a boolean `disconnectPending`, written only once the local clean-up had already failed, which
    /// is exactly what [revokedCleanupOwed] means. It never shipped, but reading it costs one key
    /// and an install that has one is an install still owed its retry row.
    ///
    /// - Parameter stored: the raw name under the phase key, nil when this device has never
    ///   written one. A phase this build wrote outranks the boolean, which by then is a leftover:
    ///   the two disagree only when a disconnect has run since, and it is the later word that
    ///   counts.
    public static func migrating(stored: String?, legacyPending: Bool) -> DisconnectPhase {
        guard stored == nil, legacyPending else { return of(stored) }
        return .revokedCleanupOwed
    }
}

/// The app-wide "a disconnect is running" flag, and the lock that makes it one at a time.
///
/// docs/03's second half — "also delete the recordings" — walks the recording directory, and the
/// check that nothing is recording is made before `GoogleAuth.disconnect()`, which is a network
/// round trip. The menu bar, Siri, the Live Activity, the watch and a meeting offer can all start a
/// capture inside that wait; it has no job yet, so the core's own `Busy` guard does not see it, and
/// the clean-up would delete the file the recorder is still writing into. So every start on this
/// device opens the capture *inside* [ifOpen], and the disconnect holds the gate from before the
/// revoke until after the clean-up: the two can neither interleave nor overlap.
///
/// `@MainActor` rather than a lock of its own, which is also what makes [ifOpen] atomic: both
/// shells' models are main-actor isolated, so the read of the flag and the write that claims it are
/// one turn with no suspension between them.
@MainActor
public enum DisconnectGate {

    /// Held by whoever got here first — a start opening a capture, or a disconnect.
    private static var locked = false
    /// True only while [hold] runs: what a refused start is actually told about.
    private static var disconnecting = false
    /// Whoever is waiting for the gate, in the order they asked. Only [hold] ever queues.
    private static var waiting: [CheckedContinuation<Void, Never>] = []

    /// True while [hold] runs.
    public static var busy: Bool { disconnecting }

    /// Why a recording may not start right now, or nil when nothing is in the way.
    public static func startBlocker() -> UiMessage? {
        disconnecting ? .key("Finish disconnecting first") : nil
    }

    /// Runs [start] with the gate held for the whole of it, or refuses — nil, nothing run — when it
    /// is not free.
    ///
    /// [startBlocker] on its own is a reading and not a promise: a start suspends between it and
    /// the capture actually opening (the Mac re-reads the workflow document first), and a
    /// disconnect that took the gate inside that wait would be walking the recording
    /// directory while the capture wrote into it. So the last check and the start are the same
    /// critical section, and this is it.
    ///
    /// It refuses rather than queues because a start that arrives during a disconnect is not one to
    /// serve late: docs/12's answer to something being in the way is to say what it is, and a
    /// capture that opened a minute later, after the clean-up, is not the one the user asked for.
    public static func ifOpen<T>(_ start: () async throws -> T) async rethrows -> T? {
        guard !locked else { return nil }
        locked = true
        defer { unlock() }
        return try await start()
    }

    /// Runs [block] with every recording start refused for the whole of it, one disconnect at a
    /// time. A start that is already opening is waited for rather than cut in front of — it has no
    /// job yet either, and the disconnect's own live re-check is what answers it. The flag is
    /// cleared in a `defer`, because a gate left shut is a device that can never record again.
    public static func hold<T>(_ block: () async throws -> T) async rethrows -> T {
        while locked {
            await withCheckedContinuation { waiting.append($0) }
        }
        locked = true
        disconnecting = true
        defer {
            disconnecting = false
            unlock()
        }
        return try await block()
    }

    private static func unlock() {
        locked = false
        // One at a time, and it re-reads [locked] itself — so a waiter woken behind another that
        // got in first simply goes back to sleep.
        if !waiting.isEmpty { waiting.removeFirst().resume() }
    }
}
