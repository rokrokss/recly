import RecKit
import RecKitTestSupport
import XCTest

/// The model as the *intents* meet it. docs/13 I7: an action button or a Siri phrase can be the
/// thing that launches the process, and `perform()` then runs against a model whose core is still
/// opening — so what has to be true the instant the model exists is that the intents can find it.
@MainActor
final class RecordingModelTests: XCTestCase {
    private var dataDirectory: URL!
    private var model: RecordingModel?

    override func setUpWithError() throws {
        dataDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecPhoneTests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() async throws {
        // The load this test deliberately does not wait for; let it settle before anything else in
        // the bundle runs.
        await model?.loaded()
        RecordingIntentTarget.commands = nil
        model = nil
        // The directory is deliberately *not* removed. `load` arms a `JobRunner` and a
        // `BackgroundJobs` pass that outlive this test — there is no shutdown to call — and taking
        // the SQLite file out from under them aborts the whole test process on an uncaught Kotlin
        // exception (`cannot rollback - no transaction is active`), landing on whichever test is
        // running by then. It is a fresh UUID directory under the simulator's temp per run.
    }

    /// A cold launch from Siri, the action button or the iOS 18 Control: the process comes up, the
    /// intent runs, and the core is still opening. The registration happens in the initialiser, so
    /// the intent finds the model and waits for the load — rather than finding nothing, reporting
    /// success and recording nothing (Sol M5-L2 review).
    ///
    /// Checked synchronously after `init` on purpose: the load is a `Task` on this same actor, so
    /// it cannot have run a line yet at this point.
    func testTheIntentsFindTheModelBeforeTheCoreIsOpen() {
        // The store, not the Keychain: this host has no entitlement for one, and the load below
        // reaches the `secrets` namespace on its way to the workflow editor.
        let model = RecordingModel(
            dataDirectory: dataDirectory,
            segmentSec: 5,
            secureStore: InMemorySecureStore()
        )
        self.model = model

        XCTAssertFalse(model.isReady, "the core has not been opened yet")
        XCTAssertTrue(
            RecordingIntentTarget.commands === model,
            "an intent arriving now would have nothing to call"
        )
    }
}
