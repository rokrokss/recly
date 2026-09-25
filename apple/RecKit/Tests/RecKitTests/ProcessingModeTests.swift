#if os(macOS) || os(iOS)
import ReclyCore
import XCTest
@testable import RecKit

final class ProcessingModeTests: XCTestCase {
    func testSwitchingFromAutomaticExternalSpeechCreatesUsableLocalOptionsAndKeepsProviderFields() {
        let draft = ProcessingDraft.companion.from(settings: ProcessingSettings.companion.defaults())
        draft.mode = .external
        draft.language = .auto
        draft.provider = "assemblyai"
        draft.model = "saved-model"

        draft.selectAppleTranscriptionMode(.local, preferredLanguage: .ko)

        XCTAssertEqual(draft.mode, .local)
        XCTAssertEqual(draft.language, .ko)
        XCTAssertTrue(draft.settings().transcription.diarize, "the runtime enables speaker separation only when supported")
        XCTAssertEqual(draft.provider, "assemblyai")
        XCTAssertEqual(draft.secretRef, "assemblyai", "the key is named after its provider")
        XCTAssertEqual(draft.model, "saved-model")

        draft.language = .fr
        draft.selectAppleTranscriptionMode(.local, preferredLanguage: .ko)
        XCTAssertEqual(draft.language, .fr, "an explicit supported language is kept")
        draft.selectAppleTranscriptionMode(.external, preferredLanguage: .ko)
        XCTAssertEqual(draft.provider, "assemblyai")
        XCTAssertEqual(draft.language, .fr)
    }
}
#endif
