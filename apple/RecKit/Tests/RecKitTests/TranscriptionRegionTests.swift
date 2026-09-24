import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

@MainActor
final class TranscriptionRegionTests: XCTestCase {
    private final class Region: AppStoreRegion {
        var code: String?
        init(_ code: String?) { self.code = code }
        func __countryCode() async throws -> String? { code }
    }

    private func bridge(_ region: Region) async throws -> CoreBridge {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return try await CoreBridge.make(
            platform: .ios, deviceName: "Test iPhone",
            dataDirectory: directory, secureStore: InMemorySecureStore(),
            transcriptionPolicy: TranscriptionPolicy(region: region)
        )
    }

    /// The provider list the recording processing settings offer follows the storefront region.
    func testProviderListFollowsRegionChangesAndAnUnknownRegionDoesNotReuseAnAllowance() async throws {
        let region = Region(nil)
        let bridge = try await bridge(region)
        let model = ProcessingSettingsModel(core: bridge.core)
        await model.refreshProviders()
        XCTAssertFalse(model.providers.contains("openai"))
        XCTAssertTrue(model.providers.contains("groq"))
        region.code = "USA"
        await model.refreshProviders()
        XCTAssertTrue(model.providers.contains("openai"))
        region.code = "CHN"
        await model.refreshProviders()
        XCTAssertFalse(model.providers.contains("openai"))
        XCTAssertEqual(model.providers.count, 13)
        region.code = "HKG"
        await model.refreshProviders()
        XCTAssertTrue(model.providers.contains("openai"))
        region.code = nil
        await model.refreshProviders()
        XCTAssertFalse(model.providers.contains("openai"))
    }
}
