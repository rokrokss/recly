import XCTest
@testable import RecKit

final class MobileMicrophoneSelectionTests: XCTestCase {
    private let builtIn = MobileMicrophoneSelection.Input(id: "built-in", priority: 2)
    private let headset = MobileMicrophoneSelection.Input(id: "bluetooth", priority: 1)
    private let usb = MobileMicrophoneSelection.Input(id: "usb", priority: 0)

    func testExternalInputWinsOverBuiltInAndWiredWinsOverBluetooth() {
        XCTAssertEqual(MobileMicrophoneSelection.choose([builtIn, headset], current: builtIn.id), headset.id)
        XCTAssertEqual(MobileMicrophoneSelection.choose([builtIn, headset, usb], current: headset.id), usb.id)
    }

    func testCurrentDeviceIsStableAmongInputsWithTheSamePriority() {
        let second = MobileMicrophoneSelection.Input(id: "second", priority: 1)
        XCTAssertEqual(MobileMicrophoneSelection.choose([headset, second], current: second.id), second.id)
        XCTAssertEqual(MobileMicrophoneSelection.choose([second, headset], current: nil), headset.id)
    }

    func testDisconnectAndReconnectSelectOnlyAvailableInputs() {
        XCTAssertEqual(MobileMicrophoneSelection.choose([builtIn], current: headset.id), builtIn.id)
        XCTAssertEqual(MobileMicrophoneSelection.choose([builtIn, headset], current: builtIn.id), headset.id)
        XCTAssertNil(MobileMicrophoneSelection.choose([], current: headset.id))
    }
}
