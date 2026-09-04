import Foundation
import XCTest
@testable import RecKit

/// docs/11 A5: `Upload on Wi-Fi only` is two flags and not one, and they go on the **request**.
///
/// The regression this exists for: they were on the session configuration too, where
/// `allowsCellularAccess` is a cap rather than a default — a background session opened while the
/// switch was on would have held every later task to Wi-Fi whatever the user did with the switch
/// afterwards, and its identifier admits no second session to replace it with.
final class UploadNetworkTests: XCTestCase {
    private let url = URL(string: "https://www.googleapis.com/upload")!

    func testWifiOnlyClosesBothDoorsOnARequest() {
        var request = URLRequest(url: url)

        UploadNetwork.apply(wifiOnly: true, to: &request)
        XCTAssertFalse(request.allowsCellularAccess)
        XCTAssertFalse(request.allowsExpensiveNetworkAccess)
    }

    /// The whole point of reading the setting per task: it has to be relaxable, not only tightenable.
    func testEveryTaskReadsTheSwitchAgainSoItCanBeTurnedBackOff() {
        let defaults = UserDefaults.standard
        let before = defaults.object(forKey: UploadNetwork.key)
        defer { defaults.set(before, forKey: UploadNetwork.key) }

        // What [URLSessionUploads.start] does for one chunk, which is the only place the answer is
        // read: nothing between the switch and the task remembers the last one.
        func next() -> URLRequest {
            var request = URLRequest(url: url)
            UploadNetwork.apply(wifiOnly: UploadNetwork.wifiOnly, to: &request)
            return request
        }

        UploadNetwork.wifiOnly = true
        XCTAssertFalse(next().allowsCellularAccess)
        XCTAssertFalse(next().allowsExpensiveNetworkAccess)

        UploadNetwork.wifiOnly = false
        XCTAssertTrue(next().allowsCellularAccess)
        XCTAssertTrue(next().allowsExpensiveNetworkAccess)

        UploadNetwork.wifiOnly = true
        XCTAssertFalse(next().allowsCellularAccess)
        XCTAssertFalse(next().allowsExpensiveNetworkAccess)
    }

    /// The default is Android's default (`AppSettings.wifiOnly`, `false`): a user who has not
    /// answered has not asked for their recordings to wait.
    func testTheSettingIsOffUntilItIsWritten() {
        let defaults = UserDefaults.standard
        let before = defaults.object(forKey: UploadNetwork.key)
        defer { defaults.set(before, forKey: UploadNetwork.key) }

        defaults.removeObject(forKey: UploadNetwork.key)
        XCTAssertFalse(UploadNetwork.wifiOnly)

        UploadNetwork.wifiOnly = true
        XCTAssertTrue(UploadNetwork.wifiOnly)
    }
}
