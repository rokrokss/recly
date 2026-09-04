import Foundation
import ReclyCore
import XCTest
@testable import RecKit

/// docs/03 "보관 · 삭제" · ADR-017: what "still on this device only" counts, now that the local parts
/// are a seven-day cache rather than the proof they used to be.
///
/// The regression this is about: the delete dialog and the disconnect warning both counted the part
/// files on disk, so for the whole week between an upload and the sweep they told the user their
/// recording was about to be lost while Drive was holding every part of it.
final class RetentionCountTests: XCTestCase {

    /// The week between the upload and the sweep. The files are all still here and none of them is
    /// the user's last copy, so there is nothing for the dialog to warn about.
    func testAPartOnDiskThatDriveHasIsNotCounted() {
        XCTAssertEqual(Retention.unuploaded(partsOnDisk: 3, uploaded: true), 0)
        XCTAssertFalse(Retention.staysHere(partsOnDisk: 3, uploaded: true))
    }

    /// A recording no job has put into Drive: every file of it that is here is the only copy there
    /// is, and that is the number the dialog leads with.
    func testThePartsOfARecordingDriveHasNotGotAreCounted() {
        XCTAssertEqual(Retention.unuploaded(partsOnDisk: 3, uploaded: false), 3)
        XCTAssertTrue(Retention.staysHere(partsOnDisk: 3, uploaded: false))
    }

    /// Nothing left on this device is nothing this device can lose, whichever way the upload went.
    func testARecordingWithNoFilesHereCountsForNothing() {
        XCTAssertEqual(Retention.unuploaded(partsOnDisk: 0, uploaded: false), 0)
        XCTAssertEqual(Retention.unuploaded(partsOnDisk: 0, uploaded: true), 0)
        XCTAssertFalse(Retention.staysHere(partsOnDisk: 0, uploaded: false))
        XCTAssertFalse(Retention.staysHere(partsOnDisk: 0, uploaded: true))
    }
}

/// docs/03 "연결 해제": the one thing the disconnect dialog has to refuse.
final class DisconnectPromptTests: XCTestCase {

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    /// The regression: "also delete the recordings" while a capture is running could delete the
    /// recording that is being written. The core's `Busy` guard is over the *queue*, and a capture
    /// that has not been stopped yet has no job in it — so nothing downstream covers this.
    func testTheConfirmIsOffWhileTheRecorderIsRunning() {
        let recording = DisconnectPrompt(unuploaded: 2, recording: true)

        XCTAssertFalse(recording.canConfirm)
        XCTAssertNotNil(recording.blocker)
    }

    /// And nothing else turns it off: a disconnect with unuploaded recordings is exactly the case
    /// the warning's count is for, not a case it refuses.
    func testAnIdleRecorderCanDisconnectWhateverIsStillOnTheDevice() {
        for unuploaded in [0, 7] {
            let prompt = DisconnectPrompt(unuploaded: unuploaded, recording: false)
            XCTAssertTrue(prompt.canConfirm)
            XCTAssertNil(prompt.blocker)
        }
    }

    /// The dialog may stand — or survive a dismissed popover — while a recording finishes, so the
    /// confirm re-reads the state and re-presents when a warning appeared that the dialog never
    /// showed. Warnings that only lessened do not re-ask, and `recording` is the live guards'
    /// business.
    func testAConfirmRepresentsOnlyWhenTheFreshStateWarnsMore() {
        let shown = DisconnectPrompt(unuploaded: 1)

        XCTAssertTrue(DisconnectPrompt(unuploaded: 2).warnsMore(than: shown))
        XCTAssertFalse(DisconnectPrompt(unuploaded: 1).warnsMore(than: shown), "nothing changed")
        XCTAssertFalse(DisconnectPrompt(unuploaded: 0).warnsMore(than: shown), "a lessened warning stands")
        XCTAssertFalse(
            DisconnectPrompt(unuploaded: 1, recording: true).warnsMore(than: shown),
            "recording has its own live guard"
        )
    }

    /// The re-read both shells make at the confirm ([DisconnectPrompt.rewarning]), on the one input
    /// it can be asked about without a database: a core that is not open has nothing to re-read, and
    /// a disconnect over one would refuse itself further down anyway — so it is not what stops here.
    func testAConfirmWithNoCoreOpenHasNothingToRePresent() async {
        let fresh = await DisconnectPrompt.rewarning(
            core: nil,
            recording: false,
            shown: DisconnectPrompt(unuploaded: 1)
        )

        XCTAssertNil(fresh)
    }

    /// docs/12 "종료 감지": never an automatic stop. The dialog says what is in the way and the user
    /// stops the recording themselves — so the line has to be readable in both languages.
    func testTheBlockerSaysWhatIsInTheWayInBothLanguages() {
        let prompt = DisconnectPrompt(unuploaded: 0, recording: true)

        AppLanguage.current = .en
        let english = prompt.blocker
        AppLanguage.current = .ko
        let korean = prompt.blocker

        XCTAssertEqual(english, "Stop the recording first")
        XCTAssertNotNil(korean)
        XCTAssertNotEqual(korean, english, "the catalog gave the key back")
    }

}

/// docs/03 "연결 해제" · docs/06: the decisions a disconnect makes at the moment it *runs*, which is
/// not the moment its warning was built.
final class DisconnectGuardTests: XCTestCase {

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    /// The regression: a disconnect whose local half failed leaves `REVOKED_CLEANUP_OWED` on disk
    /// and the account already cleared — the grant went with the first attempt. A user who then
    /// signed in again and pressed Disconnect had *that* account's grant revoked by a retry which
    /// owed nothing but a local clean-up.
    func testARetryOfAnOwedCleanupHasNoGrantLeftToTakeAway() {
        XCTAssertFalse(DisconnectGuard.revokes(phase: .revokedCleanupOwed, signedIn: true))
        XCTAssertFalse(DisconnectGuard.revokes(phase: .revokedCleanupOwed, signedIn: false))
    }

    /// The other process-death window, and the opposite answer: `REVOKE_PENDING` was written
    /// *before* the revoke, so it is not known whether the grant went. A sign-in that is still
    /// restored is a revoke that never happened, and the retry tries it again; one that is gone
    /// means the revoke got as far as clearing it, and the retry goes on to the clean-up.
    func testARetryOfAPendingRevokeAsksTheSignInWhetherItEverHappened() {
        XCTAssertTrue(DisconnectGuard.revokes(phase: .revokePending, signedIn: true))
        XCTAssertFalse(DisconnectGuard.revokes(phase: .revokePending, signedIn: false))
    }

    /// A first disconnect revokes exactly when there is something to revoke.
    func testAFirstDisconnectRevokesWhateverGrantThisDeviceHolds() {
        XCTAssertTrue(DisconnectGuard.revokes(phase: .none, signedIn: true))
        XCTAssertFalse(DisconnectGuard.revokes(phase: .none, signedIn: false))
    }

    /// The store is not the only thing that writes this key, and a build that does not know a name
    /// owes nothing rather than crashing on it.
    func testAPhaseNameThisBuildDoesNotKnowIsNothingOwed() {
        XCTAssertEqual(DisconnectPhase.of("REVOKE_PENDING"), .revokePending)
        XCTAssertEqual(DisconnectPhase.of(nil), DisconnectPhase.none)
        XCTAssertEqual(DisconnectPhase.of("REVOKE_HALFWAY"), DisconnectPhase.none)
        XCTAssertFalse(DisconnectPhase.none.owed)
        XCTAssertTrue(DisconnectPhase.revokePending.owed)
        XCTAssertTrue(DisconnectPhase.revokedCleanupOwed.owed)
    }

    /// docs/00-decisions.md: the build before this one wrote a boolean instead of a phase, set only
    /// once the local clean-up had already failed — which is what `REVOKED_CLEANUP_OWED` means. It
    /// never shipped, but an install that has one is an install still owed its retry row, and
    /// dropping the key would drop the row with it.
    func testTheBooleanAnOlderInstallHasIsReadAsAnOwedCleanup() {
        XCTAssertEqual(
            DisconnectPhase.migrating(stored: nil, legacyPending: true), .revokedCleanupOwed
        )
        XCTAssertEqual(
            DisconnectPhase.migrating(stored: nil, legacyPending: false), DisconnectPhase.none
        )
    }

    /// And a phase this build has written outranks it: by then the boolean is a leftover of a
    /// disconnect that has since run again, and it is the later word that counts.
    func testAPhaseThisBuildWroteOutranksTheOlderBoolean() {
        XCTAssertEqual(
            DisconnectPhase.migrating(stored: "NONE", legacyPending: true), DisconnectPhase.none
        )
        XCTAssertEqual(
            DisconnectPhase.migrating(stored: "REVOKE_PENDING", legacyPending: true), .revokePending
        )
        // A name this build does not know is still nothing owed, boolean or no boolean.
        XCTAssertEqual(
            DisconnectPhase.migrating(stored: "REVOKE_HALFWAY", legacyPending: true),
            DisconnectPhase.none
        )
    }

    /// The regression: the shells read "signed in" off the account *email*, and both a restore that
    /// failed and a Google user who simply carries no email land there as nil. The disconnect then
    /// skipped the revoke and deleted every key on the device while the grant it was about was
    /// never touched — so the reading is the credential, and a restore that failed is a third
    /// answer rather than a "no".
    func testTheRevokeIsDecidedByTheCredentialAndNotByTheEmail() {
        XCTAssertEqual(
            DisconnectGuard.revokeDecision(phase: .none, restoration: .restored(hasCredential: true)),
            .revoke
        )
        XCTAssertEqual(
            DisconnectGuard.revokeDecision(phase: .none, restoration: .restored(hasCredential: false)),
            .skip
        )
        XCTAssertEqual(DisconnectGuard.revokeDecision(phase: .none, restoration: .none), .skip)
        // The retry of an owed clean-up still has no grant of its own to take away.
        XCTAssertEqual(
            DisconnectGuard.revokeDecision(
                phase: .revokedCleanupOwed, restoration: .restored(hasCredential: true)
            ),
            .skip
        )
        XCTAssertEqual(
            DisconnectGuard.revokeDecision(
                phase: .revokePending, restoration: .restored(hasCredential: true)
            ),
            .revoke
        )
    }

    /// A restore that failed is not a device that is signed out: revoking might take away an
    /// account this disconnect was never about, and skipping would delete every key over a grant
    /// Google is still listing. So neither is done — except on the one phase that already knows the
    /// grant is gone, whose clean-up needs no sign-in at all.
    func testARestoreThatFailedStopsTheDisconnectRatherThanGuessingAtIt() {
        XCTAssertEqual(
            DisconnectGuard.revokeDecision(phase: .none, restoration: .failed("keychain is locked")),
            .unknown
        )
        XCTAssertEqual(
            DisconnectGuard.revokeDecision(
                phase: .revokePending, restoration: .failed("keychain is locked")
            ),
            .unknown
        )
        XCTAssertEqual(
            DisconnectGuard.revokeDecision(
                phase: .revokedCleanupOwed, restoration: .failed("keychain is locked")
            ),
            .skip
        )
    }

    /// What it says instead, and what the store refusing a write says: both are about the button
    /// the user is already looking at, so both are sentences in both languages.
    func testTheRefusalsSayWhatToDoInBothLanguages() {
        AppLanguage.current = .en
        let english = (DisconnectGuard.restoreUnknown.text, DisconnectGuard.saveFailed.text)
        AppLanguage.current = .ko
        let korean = (DisconnectGuard.restoreUnknown.text, DisconnectGuard.saveFailed.text)

        XCTAssertEqual(english.0, "Could not check the Google sign-in — try Disconnect again")
        XCTAssertEqual(english.1, "Could not save the disconnect state — try again")
        XCTAssertNotEqual(korean.0, english.0, "the catalog gave the key back")
        XCTAssertNotEqual(korean.1, english.1, "the catalog gave the key back")
    }

    /// The other end of the same rule, and the one that makes it airtight: while a disconnect is
    /// owed there is no second account to have signed in with — and no signing out either, which
    /// would take the account [DisconnectGuard.revokes] reads.
    func testSigningInAndOutIsRefusedWhileADisconnectIsStillOwed() {
        XCTAssertNil(DisconnectGuard.signInBlocker(pending: false))

        AppLanguage.current = .en
        let english = DisconnectGuard.signInBlocker(pending: true)?.text
        AppLanguage.current = .ko
        let korean = DisconnectGuard.signInBlocker(pending: true)?.text

        XCTAssertEqual(english, "Finish disconnecting first")
        XCTAssertNotNil(korean)
        XCTAssertNotEqual(korean, english, "the catalog gave the key back")
    }

    /// The regression: [DisconnectPrompt.recording] is read when the warning opens, and the warning
    /// stands for as long as the user leaves it there. A capture started meanwhile — from the watch,
    /// from Siri, from the Live Activity, from the menu bar — was then deleted by an "also delete
    /// the recordings" the dialog had already decided was safe. The recorder is read once more at
    /// the moment the disconnect runs, and this is that reading.
    func testACaptureThatStartedWhileTheWarningStoodStopsTheDisconnect() {
        XCTAssertNil(DisconnectGuard.liveBlocker(recording: false))

        AppLanguage.current = .en
        let english = DisconnectGuard.liveBlocker(recording: true)?.text
        AppLanguage.current = .ko
        let korean = DisconnectGuard.liveBlocker(recording: true)?.text

        XCTAssertEqual(english, "Stop the recording first")
        XCTAssertNotNil(korean)
        XCTAssertNotEqual(korean, english, "the catalog gave the key back")
    }
}

/// docs/03 · docs/06: the *order* a disconnect writes things down in, which is the whole of what
/// survives a process death in the middle of one.
final class DisconnectOrderingTests: XCTestCase {

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    /// The regression: nothing was written before the revoke, and `disconnect()` clears this
    /// device's credentials on both of its branches. A process killed inside that call came back
    /// signed out, with the queue, the keys and the sync state all still here and nothing on screen
    /// offering to finish the job.
    func testTheRevokeIsPendingOnDiskBeforeItIsEvenTried() async {
        let store = FakeStore()
        var whenRevoked: [DisconnectPhase] = []

        let attempt = await DisconnectGuard.revoking(persist: { store.phase($0) }) {
            whenRevoked = store.phases
            return .tried(failure: nil)
        }

        XCTAssertEqual(attempt, .tried(failure: nil))
        XCTAssertEqual(whenRevoked, [.revokePending], "the revoke ran before the phase was on disk")
        XCTAssertEqual(store.phases, [.revokePending, .revokedCleanupOwed])
    }

    /// The regression: `UserDefaults.synchronize()` answers whether the write actually reached the
    /// store and the answer was dropped on the floor. A store that refused left `GoogleAuth`
    /// deleting this device's credentials with nothing on disk saying a disconnect had ever
    /// started — the one thing the phase exists to survive. So a phase that did not commit stops
    /// the revoke before it is even asked for.
    func testAPhaseThatWouldNotCommitStopsTheRevokeBeforeItIsAsked() async {
        let store = FakeStore()
        store.commits = false
        var revoked = false

        let attempt = await DisconnectGuard.revoking(persist: { store.phase($0) }) {
            revoked = true
            return .tried(failure: nil)
        }

        XCTAssertEqual(attempt, .notSaved)
        XCTAssertFalse(revoked, "the grant was revoked over a phase that is not on disk")
        XCTAssertEqual(store.phases, [])
    }

    /// And the phase is not moved on over one either: a debt that could not be written left the
    /// token where it was, so the revoke is still owed and `REVOKED_CLEANUP_OWED` — which says the
    /// grant is gone — would be a lie the retry then acts on.
    func testARevokeThatCouldNotBeWrittenDownLeavesThePhasePending() async {
        let store = FakeStore()

        let attempt = await DisconnectGuard.revoking(persist: { store.phase($0) }) { .notSaved }

        XCTAssertEqual(attempt, .notSaved)
        XCTAssertEqual(store.phases, [.revokePending])
    }

    /// The regression the phase alone could not catch: `signOut` on the failure branch clears the
    /// SDK's Keychain entry, and a process killed between that and the debt write came back with no
    /// token — so the retry read the missing token as "the revoke happened", ran on to `NONE`, and
    /// reported success over a Google grant that was still standing.
    func testAFailedRevokeOwesTheDebtBeforeTheTokenItIsAboutIsDeleted() async {
        var order: [String] = []

        let attempt = await DisconnectGuard.owingDebt(
            owe: {
                order.append("owe(\($0))")
                return true
            },
            revoke: {
                order.append("revoke")
                return "network is down"
            },
            forgetTokens: { order.append("forget") }
        )

        XCTAssertEqual(attempt, .tried(failure: "network is down"))
        XCTAssertEqual(order, ["revoke", "owe(true)", "forget"])
    }

    /// And a debt that would not commit stops the sign-out for the same reason the phase stops the
    /// revoke: `signOut` deletes the token the retry reads to tell a revoke that happened from one
    /// that never did, and a debt that is not on disk is one the retry cannot know about. The
    /// token stays, the phase stays pending, and the retry revokes again.
    func testADebtThatWouldNotCommitKeepsTheTokenItIsAbout() async {
        let store = FakeStore()
        store.commits = false
        var forgotten = false

        let attempt = await DisconnectGuard.owingDebt(
            owe: { store.debt($0) },
            revoke: { "network is down" },
            forgetTokens: { forgotten = true }
        )

        XCTAssertEqual(attempt, .notSaved)
        XCTAssertFalse(forgotten, "the token went with a debt that was never written down")
    }

    /// A revoke that succeeded says nothing about a debt already standing: the app keeps no account
    /// identity once it is done, so the grant just taken away may belong to another account than
    /// the one still listing Recly. Only the user's own word clears it.
    func testASuccessfulRevokeNeitherOwesNorClearsADebt() async {
        let store = FakeStore()
        var forgotten = false

        let attempt = await DisconnectGuard.owingDebt(
            owe: { store.debt($0) },
            revoke: { nil },
            forgetTokens: { forgotten = true }
        )

        XCTAssertEqual(attempt, .tried(failure: nil))
        XCTAssertEqual(store.debts, [], "a revoke that worked wrote something about the debt")
        XCTAssertFalse(forgotten, "the tokens are `disconnect()`'s to clear when it succeeded")
    }

    /// The regression: the phase was written after `core.disconnect` returned, so a process that
    /// died inside the clean-up came back with the account cleared, every key and the whole queue
    /// still on the device, and nothing on screen saying so.
    func testTheCleanupIsOwedOnDiskBeforeItIsTriedAndClearedOnlyAfterwards() async {
        let store = FakeStore()
        var whenCleaning: [DisconnectPhase] = []

        let result = await DisconnectGuard.owingCleanup(persist: { store.phase($0) }) {
            whenCleaning = store.phases
            return DisconnectResult(deletedRecordings: 2, busyRecordings: [])
        }

        XCTAssertEqual(whenCleaning, [.revokedCleanupOwed])
        XCTAssertEqual(store.phases, [.revokedCleanupOwed, DisconnectPhase.none])
        XCTAssertEqual(result?.deletedRecordings, 2)
    }

    /// The same rule as the revoke's, over the half that deletes the keys, the queue and the sync
    /// state: a phase that would not commit stops the clean-up before it is run, so the device is
    /// left whole rather than emptied with nothing on disk saying why.
    func testAPhaseThatWouldNotCommitStopsTheCleanupBeforeItIsRun() async {
        let store = FakeStore()
        store.commits = false
        var cleaned = false

        let result = await DisconnectGuard.owingCleanup(persist: { store.phase($0) }) {
            cleaned = true
            return DisconnectResult(deletedRecordings: 2, busyRecordings: [])
        }

        XCTAssertNil(result)
        XCTAssertFalse(cleaned, "the device was cleaned out over a phase that is not on disk")
        XCTAssertEqual(store.phases, [])
    }

    /// Returning is not the same as having finished: the recordings a `RUNNING` job would not let
    /// go of kept their files *and* their queue rows, so that clean-up is still owed and the row
    /// that runs the rest of it has to stay on screen.
    func testAClosureKeptForABusyJobLeavesTheCleanupOwed() async {
        let store = FakeStore()

        _ = await DisconnectGuard.owingCleanup(persist: { store.phase($0) }) {
            DisconnectResult(deletedRecordings: 0, busyRecordings: ["rec-1"])
        }

        XCTAssertEqual(store.phases, [.revokedCleanupOwed])
    }

    /// A clean-up that threw leaves the phase owed for the same reason.
    func testACleanupThatThrewLeavesThePhaseOwed() async {
        struct Boom: Error {}
        let store = FakeStore()

        do {
            _ = try await DisconnectGuard.owingCleanup(persist: { store.phase($0) }) { throw Boom() }
            XCTFail("the throw was swallowed")
        } catch is Boom {
            // The point of the test.
        } catch {
            XCTFail("\(error)")
        }

        XCTAssertEqual(store.phases, [.revokedCleanupOwed])
    }
}

/// A store whose flush can be told to refuse, which is the whole of what the ordering tests need:
/// `UserDefaults.synchronize()` answers whether the write reached the store, and what these rules
/// are about is everything that must *not* happen when it did not.
private final class FakeStore {
    /// What actually reached the store, in order.
    private(set) var phases: [DisconnectPhase] = []
    private(set) var debts: [Bool] = []
    /// What the flush says.
    var commits = true

    func phase(_ phase: DisconnectPhase) -> Bool {
        guard commits else { return false }
        phases.append(phase)
        return true
    }

    func debt(_ owed: Bool) -> Bool {
        guard commits else { return false }
        debts.append(owed)
        return true
    }
}

/// docs/03: what the Disconnect row says once it is over, in the order the user can do something
/// about.
final class DisconnectCompletionTests: XCTestCase {

    override func setUp() {
        super.setUp()
        AppLanguage.current = .en
    }

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    private func completion(
        revokeFailure: String? = nil,
        cleanupFailure: String? = nil,
        result: DisconnectResult? = nil,
        stillListed: Bool = false
    ) -> String {
        DisconnectGuard.completion(
            revokeFailure: revokeFailure,
            cleanupFailure: cleanupFailure,
            result: result,
            stillListed: stillListed,
            device: .phone
        ).text
    }

    /// The two failures first: they are the ones the user can still do something about, and a
    /// revoke that failed outranks everything because the grant is still standing.
    func testTheFailuresComeFirstAndTheRevokeOutranksTheCleanup() {
        let both = completion(
            revokeFailure: "network is down",
            cleanupFailure: "database is locked",
            result: DisconnectResult(deletedRecordings: 3, busyRecordings: ["rec-1"]),
            stillListed: true
        )
        XCTAssertTrue(both.contains("network is down"), both)

        let cleanup = completion(
            cleanupFailure: "database is locked",
            result: DisconnectResult(deletedRecordings: 3, busyRecordings: ["rec-1"]),
            stillListed: true
        )
        XCTAssertTrue(cleanup.contains("database is locked"), cleanup)
    }

    /// Then the recordings a running job would not let go of, and only then the grant Google is
    /// still listing.
    func testABusyRecordingOutranksTheDebtAndTheDebtOutranksSuccess() {
        let busy = completion(
            result: DisconnectResult(deletedRecordings: 3, busyRecordings: ["rec-1", "rec-2"]),
            stillListed: true
        )
        XCTAssertTrue(busy.contains("2"), busy)
        XCTAssertFalse(busy.hasPrefix("Disconnected"), "a clean-up that is still owed said it was done")

        let listed = completion(
            result: DisconnectResult(deletedRecordings: 3, busyRecordings: []),
            stillListed: true
        )
        XCTAssertEqual(listed, DisconnectGuard.stillListed.text)
    }

    /// The regression this whole guard is about: the restart path skipped the revoke, so there is
    /// no failure here to read — and "Disconnected" over a grant Google is still listing is exactly
    /// the lie the debt exists to prevent.
    func testTheRestartPathNeverSaysDisconnectedOverAnOwedDebt() {
        XCTAssertEqual(completion(stillListed: true), DisconnectGuard.stillListed.text)
        AppLanguage.current = .ko
        XCTAssertNotEqual(
            DisconnectGuard.stillListed.text,
            "Google still lists Recly — remove it at myaccount.google.com/permissions",
            "the catalog gave the key back"
        )
        XCTAssertNotEqual(DisconnectGuard.debtSettled.text, "I removed it", "the catalog gave the key back")
    }

    /// The regression: the sentence said "Disconnected" over a clean-up the core had deliberately
    /// not finished — the recordings a `RUNNING` job would not let go of kept their files and their
    /// queue rows, and the phase says as much. It is the settled Windows·Android line
    /// (`disconnect.busy` · `disconnect_busy`) word for word, so a user who reads it on two devices
    /// reads the same thing, and it names what is still owed.
    func testTheBusyEndingIsTheSettledSentenceAndSaysThePhaseIsStillOwed() {
        let busy = DisconnectResult(deletedRecordings: 0, busyRecordings: ["rec-1", "rec-2"])

        AppLanguage.current = .en
        let english = completion(result: busy)
        AppLanguage.current = .ko
        let korean = completion(result: busy)

        XCTAssertEqual(
            english,
            "Google access was revoked, but 2 recording(s) were still running and were kept"
                + " — disconnect again once they have finished."
        )
        XCTAssertNotEqual(korean, english, "the catalog gave the key back")
        XCTAssertTrue(korean.contains("2"), korean)
    }

    /// And with nothing owed, the two plain endings — each naming the device it is drawn on.
    func testASuccessNamesTheDeviceItIsDrawnOn() {
        let deleted = DisconnectResult(deletedRecordings: 4, busyRecordings: [])
        XCTAssertTrue(completion(result: deleted).contains("4"))
        XCTAssertTrue(completion(result: deleted).contains("phone"))
        XCTAssertTrue(completion(result: DisconnectResult(deletedRecordings: 0, busyRecordings: []))
            .contains("phone"))

        let mac = DisconnectGuard.completion(
            revokeFailure: nil,
            cleanupFailure: nil,
            result: deleted,
            stillListed: false,
            device: .mac
        ).text
        XCTAssertTrue(mac.contains("Mac"), mac)

        // The two device sentences are the only keys not written as a literal at the `.key(` call
        // site, so the catalog scan does not reach them; this is what would notice one going
        // missing.
        AppLanguage.current = .ko
        XCTAssertFalse(completion(result: deleted).contains("phone"), "the catalog gave the key back")
    }
}

/// docs/03 · docs/12: the gate every start on this device opens its capture inside, and the one a
/// disconnect holds from before the revoke until after the clean-up.
@MainActor
final class DisconnectGateTests: XCTestCase {

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    /// The regression: the check that nothing is recording is made before `disconnect()`, which is
    /// a network round trip — and the watch, Siri, the Live Activity and the menu bar can all start
    /// a capture inside that wait. It has no job yet, so the core's own `Busy` guard does not see
    /// it, and the clean-up would delete the file the recorder is still writing into.
    func testEveryStartIsRefusedWhileTheDisconnectHoldsTheGate() async {
        XCTAssertNil(DisconnectGate.startBlocker())
        XCTAssertFalse(DisconnectGate.busy)

        var inside: String?
        await DisconnectGate.hold {
            XCTAssertTrue(DisconnectGate.busy)
            AppLanguage.current = .en
            inside = DisconnectGate.startBlocker()?.text
            let opened: String? = await DisconnectGate.ifOpen { "rec-1" }
            XCTAssertNil(opened, "a capture opened inside a disconnect")
        }

        XCTAssertEqual(inside, "Finish disconnecting first")
        XCTAssertFalse(DisconnectGate.busy)
        XCTAssertNil(DisconnectGate.startBlocker())
        let after: String? = await DisconnectGate.ifOpen { "rec-1" }
        XCTAssertEqual(after, "rec-1")
    }

    /// The other direction, and the reason [DisconnectGate.ifOpen] wraps the start rather than
    /// preceding it: a start that suspends between the check and the capture must not let a
    /// disconnect in behind it.
    /// A property rather than a local: the start runs in a `Task` of its own, and a `var` captured
    /// by two concurrent closures is not one Swift will let be written from both.
    private var order: [String] = []

    func testADisconnectWaitsForAStartThatIsAlreadyOpening() async {
        order = []
        let opening = Task { @MainActor in
            await DisconnectGate.ifOpen {
                await Task.yield()
                self.order.append("start")
            }
        }
        // Let the start take the gate before the disconnect asks for it.
        await Task.yield()
        await DisconnectGate.hold { self.order.append("disconnect") }
        _ = await opening.value

        XCTAssertEqual(order, ["start", "disconnect"])
    }

    /// A gate left shut is a device that can never record again, so a disconnect that blew up
    /// clears it on the way out.
    func testAThrowLeavesTheGateOpen() async {
        struct Boom: Error {}
        do {
            try await DisconnectGate.hold { throw Boom() }
            XCTFail("the throw was swallowed")
        } catch is Boom {
            // The point of the test.
        } catch {
            XCTFail("\(error)")
        }

        XCTAssertFalse(DisconnectGate.busy)
        XCTAssertNil(DisconnectGate.startBlocker())
        let after: String? = await DisconnectGate.ifOpen { "rec-1" }
        XCTAssertEqual(after, "rec-1")
    }
}

/// docs/12 M8 · ADR-011: the consent reminder is a question, and an entry point with nobody to ask
/// cannot answer it on the user's behalf.
final class BackgroundStartTests: XCTestCase {

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    /// The regression: `startFromIntent` went straight to the recorder, so Siri, a Shortcut, the
    /// action button and the Control all recorded a first meeting without the reminder the screen's
    /// own start cannot get past.
    func testABackgroundStartIsRefusedWhileTheReminderIsStillOwed() {
        XCTAssertNotNil(BackgroundStart.refusal(askConsent: true))
    }

    /// Answered once, or switched off: every entry point starts as before.
    func testABackgroundStartGoesAheadOnceTheReminderIsAnswered() {
        XCTAssertNil(BackgroundStart.refusal(askConsent: false))
    }

    /// The refusal is read out by Siri and shown in Shortcuts, so it is a sentence and not a code.
    func testTheRefusalSaysWhatToDoInBothLanguages() {
        AppLanguage.current = .en
        let english = BackgroundStart.refusal(askConsent: true)
        AppLanguage.current = .ko
        let korean = BackgroundStart.refusal(askConsent: true)

        XCTAssertEqual(english, "Open Recly once to answer the recording reminder")
        XCTAssertNotEqual(korean, english, "the catalog gave the key back")
    }
}

/// docs/03 · docs/06: what a disconnect whose local half failed leaves behind, in words. The grant
/// is gone either way, so the sentence is about the device and points at the one thing that can
/// still be done about it.
final class DisconnectCleanupMessageTests: XCTestCase {

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    func testTheCleanupFailureIsASentenceInBothLanguagesAndKeepsTheError() {
        let note = UiMessage.key(
            "Google access was revoked, but cleaning up this device failed: %@ — try Disconnect again",
            args: [.verbatim("database is locked")]
        )

        AppLanguage.current = .en
        let english = note.text
        AppLanguage.current = .ko
        let korean = note.text

        XCTAssertEqual(
            english,
            "Google access was revoked, but cleaning up this device failed: "
                + "database is locked — try Disconnect again"
        )
        XCTAssertNotEqual(korean, english, "the catalog gave the key back")
        XCTAssertTrue(korean.contains("database is locked"), korean)
    }
}
