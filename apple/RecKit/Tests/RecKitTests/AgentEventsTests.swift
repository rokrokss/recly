#if os(macOS)
import XCTest
@testable import RecKit

/// docs/12 "Agent connection": what the Mac app makes of `recly-events status --json`, when it
/// starts and stops `serve`, and the process itself against a stand-in for the program.
final class AgentEventsTests: XCTestCase {

    private let ready = AgentEventsStatus(
        home: "/tmp/h", tunnelId: "tunnel_x", tunnelKey: true, googleSignedIn: true
    )

    func testDecodesWhatStatusJSONPrints() throws {
        let json = """
        {"home": "/h", "tunnelId": "tunnel_x", "tunnelKey": true, "googleSignedIn": true,
         "server": {"pid": 42, "tunnelReady": true, "startedAt": "2026-10-05T12:39:28+04:00", "version": "0.1.0"},
         "drive": {"pollSeconds": 10, "announced": 2}, "subscriptions": 1, "subscriptionsEnded": false, "pending": 0,
         "lastDelivery": {"at": "2026-10-05T12:10:48+04:00", "status": 200}}
        """
        let status = try JSONDecoder().decode(AgentEventsStatus.self, from: Data(json.utf8))
        XCTAssertEqual(status.server?.pid, 42)
        XCTAssertTrue(status.setUp)
        XCTAssertEqual(status.subscriptions, 1)
        XCTAssertNil(status.googleAccountId)
        let signedIn = try JSONDecoder().decode(AgentEventsStatus.self, from: Data("""
        {"home": "/h", "tunnelKey": true, "googleSignedIn": true, "googleAccountId": "perm-1", "drive": {}, "subscriptions": 0, "subscriptionsEnded": false}
        """.utf8))
        XCTAssertEqual(signedIn.googleAccountId, "perm-1")

        let fresh = try JSONDecoder().decode(AgentEventsStatus.self, from: Data("""
        {"home": "/h", "tunnelKey": false, "googleSignedIn": false, "server": null, "drive": {"pollSeconds": 10, "announced": 0}, "subscriptions": 0, "subscriptionsEnded": false, "pending": 0}
        """.utf8))
        XCTAssertNil(fresh.server)
        XCTAssertFalse(fresh.setUp)
    }

    func testGoogleEndsWhenGoogleRefusesTheSignIn() {
        var status = ready
        status.drive.lastError = #"oauth2: "invalid_grant" "Token has been expired or revoked.""#
        XCTAssertTrue(status.googleEnded)
        status.drive.lastError = "drive: HTTP 503: backend"
        XCTAssertFalse(status.googleEnded)
    }

    func testPhaseSaysTheMostUrgentThingFirst() {
        func phase(enabled: Bool = true, available: Bool = true, signingIn: Bool = false, gaveUp: Bool = false,
                   owned: Bool = true, _ status: AgentEventsStatus?) -> AgentEventsPhase {
            .of(enabled: enabled, available: available, signingIn: signingIn, gaveUp: gaveUp, owned: owned, status: status)
        }
        var running = ready
        running.server = .init(pid: 7, tunnelReady: true, tunnelError: nil)
        XCTAssertEqual(phase(available: false, running), .unavailable)
        XCTAssertEqual(phase(enabled: false, running), .off)
        XCTAssertEqual(phase(signingIn: true, running), .signingIn)
        XCTAssertEqual(phase(gaveUp: true, running), .gaveUp)
        XCTAssertEqual(phase(nil), .starting)
        XCTAssertEqual(phase(AgentEventsStatus(home: "/h")), .needsSetup)
        XCTAssertEqual(phase(ready), .starting)
        XCTAssertEqual(phase(owned: false, running), .elsewhere)
        XCTAssertEqual(phase(running), .running(.none))

        var subscribed = running
        subscribed.subscriptions = 1
        XCTAssertEqual(phase(subscribed), .running(.active))
        var ended = running
        ended.subscriptionsEnded = true
        XCTAssertEqual(phase(ended), .running(.ended), "an agent that stopped listening is told apart from none yet")
        var connecting = running
        connecting.server?.tunnelReady = false
        XCTAssertEqual(phase(connecting), .connecting)
        var broken = connecting
        broken.server?.tunnelError = "tunnel: unauthorized"
        XCTAssertEqual(phase(broken), .tunnelError)
        var signedOut = running
        signedOut.drive.lastError = "invalid_grant"
        XCTAssertEqual(phase(signedOut), .googleEnded)
    }

    func testAStorageItCannotWatchIsSaidBeforeAnythingElseAndAnUnreadOneWaits() {
        var running = ready
        running.server = .init(pid: 7, tunnelReady: true, tunnelError: nil)
        func phase(enabled: Bool = true, available: Bool = true, _ drive: Bool?) -> AgentEventsPhase {
            .of(enabled: enabled, available: available, driveStorage: drive, signingIn: false, gaveUp: false, owned: true, status: running)
        }
        XCTAssertEqual(phase(available: false, false), .unavailable)
        XCTAssertEqual(phase(false), .notDrive)
        XCTAssertEqual(phase(enabled: false, false), .notDrive, "said even while off")
        XCTAssertEqual(phase(nil), .starting)
        XCTAssertEqual(phase(enabled: false, nil), .off)
    }

    func testTheAccountIsTheSameAnotherOrNotKnownFromOneSide() {
        XCTAssertEqual(AgentEventsAccount.of(agent: "perm-1", upload: "perm-1"), .same)
        XCTAssertEqual(AgentEventsAccount.of(agent: "perm-1", upload: "perm-2"), .different)
        XCTAssertEqual(AgentEventsAccount.of(agent: nil, upload: "perm-1"), .unknown)
        XCTAssertEqual(AgentEventsAccount.of(agent: "perm-1", upload: nil), .unknown)
    }

    func testStartsOnlyWhenOnSetUpAndNothingElseAnswers() {
        func act(enabled: Bool = true, gaveUp: Bool = false, child: Bool = false, _ status: AgentEventsStatus?) -> AgentEventsAction {
            .reconcile(enabled: enabled, gaveUp: gaveUp, childRunning: child, status: status)
        }
        XCTAssertEqual(act(ready), .start)
        XCTAssertEqual(act(nil), .none)
        XCTAssertEqual(act(AgentEventsStatus(home: "/h")), .none)
        var elsewhere = ready
        elsewhere.server = .init(pid: 9, tunnelReady: true, tunnelError: nil)
        XCTAssertEqual(act(elsewhere), .none)
        XCTAssertEqual(act(child: true, ready), .none)
        XCTAssertEqual(act(enabled: false, child: true, ready), .stop)
        XCTAssertEqual(act(gaveUp: true, child: true, ready), .stop)
        XCTAssertEqual(act(enabled: false, ready), .none)
    }

    func testRestartsAreThreeInTenMinutes() {
        var restarts = AgentEventsRestarts()
        let start = Date()
        XCTAssertTrue(restarts.allow(at: start))
        XCTAssertTrue(restarts.allow(at: start + 1))
        XCTAssertTrue(restarts.allow(at: start + 2))
        XCTAssertFalse(restarts.allow(at: start + 3))
        XCTAssertTrue(restarts.allow(at: start + 601))
    }

    func testTheKeyNeverGoesInTheArguments() {
        XCTAssertEqual(AgentEventsCommand.saveTunnel(id: "tunnel_x", hasKey: true),
                       ["init", "--tunnel-id", "tunnel_x", "--tunnel-key-stdin", "--no-check"])
        XCTAssertEqual(AgentEventsCommand.saveTunnel(id: "tunnel_x", hasKey: false),
                       ["init", "--tunnel-id", "tunnel_x", "--no-check"])
    }

    /// A build without the program says so before anything runs, and runs nothing.
    @MainActor
    func testABuildWithoutTheProgramSaysSo() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "agent-\(UUID().uuidString)"))
        let controller = AgentEventsController(executable: nil, defaults: defaults)
        defer { controller.shutdown() }
        XCTAssertEqual(controller.phase, .unavailable)
    }

    /// The whole loop against a shell script that answers `status --json` and `serve` the way the
    /// program does: on, it starts the server and calls it its own, and compares its account with
    /// the upload account once; off, or a storage it cannot watch, stops it.
    @MainActor
    func testRunsServeWhileOnAndStopsItWhenOff() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("agent-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let script = dir.appendingPathComponent("recly-events")
        try """
        #!/bin/sh
        dir="$(dirname "$0")"
        case "$1" in
        status)
          server=null
          if [ -f "$dir/serve.pid" ] && kill -0 "$(cat "$dir/serve.pid")" 2>/dev/null; then
            server="{\\"pid\\": $(cat "$dir/serve.pid"), \\"tunnelReady\\": true}"
          fi
          printf '{"home":"%s","tunnelId":"tunnel_x","tunnelKey":true,"googleSignedIn":true,"googleAccountId":"perm-1","server":%s,"drive":{},"subscriptions":0,"subscriptionsEnded":false}\\n' "$dir" "$server"
          ;;
        serve)
          echo $$ > "$dir/serve.pid"
          exec sleep 600
          ;;
        esac
        """.write(to: script, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: script.path)
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "agent-\(UUID().uuidString)"))

        let controller = AgentEventsController(executable: script, defaults: defaults)
        defer { controller.shutdown() }
        var asks = 0
        controller.uploadAccount = {
            asks += 1
            return "perm-1"
        }
        XCTAssertEqual(controller.phase, .off)
        controller.enabled = true
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.appendingPathComponent("serve.pid").path),
                       "not started before the storage was read")
        controller.driveStorage = true
        try await waitFor(controller, .running(.none))
        let pid = try servePid(dir)
        XCTAssertEqual(kill(pid, 0), 0, "serve is running")
        XCTAssertEqual(controller.account, .same)
        controller.refreshNow()
        try await Task.sleep(nanoseconds: 500_000_000)
        XCTAssertEqual(asks, 2, "asked once, and again when the section is shown")

        controller.enabled = false
        try await waitFor(controller, .off)
        try await waitUntilGone(pid)

        controller.enabled = true
        try await waitFor(controller, .running(.none))
        let second = try servePid(dir)
        controller.driveStorage = false
        try await waitFor(controller, .notDrive)
        try await waitUntilGone(second)
    }

    private func servePid(_ dir: URL) throws -> Int32 {
        try XCTUnwrap(Int32(String(contentsOf: dir.appendingPathComponent("serve.pid"), encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines)))
    }

    private func waitUntilGone(_ pid: Int32) async throws {
        for _ in 0..<50 where kill(pid, 0) == 0 {
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertNotEqual(kill(pid, 0), 0, "serve was stopped")
    }

    /// Against the program the Mac app bundles (`make mac-helper` builds it), in a home of its own:
    /// the switch asks for set-up, and a saved tunnel reaches recly-events with the key on stdin.
    @MainActor
    func testTalksToTheProgramItBundles() async throws {
        let apple = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let program = apple.appendingPathComponent("build/recly-events/recly-events")
        try XCTSkipUnless(FileManager.default.isExecutableFile(atPath: program.path), "make mac-helper builds it")
        let home = FileManager.default.temporaryDirectory.appendingPathComponent("agent-home-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: home) }
        // The children inherit it: nothing here touches the user's own recly-events.
        setenv("RECLY_EVENTS_HOME", home.path, 1)
        defer { unsetenv("RECLY_EVENTS_HOME") }
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "agent-\(UUID().uuidString)"))

        let controller = AgentEventsController(executable: program, defaults: defaults)
        defer { controller.shutdown() }
        controller.driveStorage = true
        controller.enabled = true
        try await waitFor(controller, .needsSetup)

        controller.saveTunnel(id: "tunnel_test", key: "sk-test")
        for _ in 0..<100 where controller.tunnelId != "tunnel_test" {
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertEqual(controller.tunnelId, "tunnel_test")
        XCTAssertFalse(controller.saveFailed)
        XCTAssertEqual(controller.phase, .needsSetup, "no Google sign-in yet")
        let key = home.appendingPathComponent("tunnel-key")
        XCTAssertEqual(try String(contentsOf: key, encoding: .utf8), "sk-test")
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: key.path)[.posixPermissions] as? Int, 0o600)
    }

    @MainActor
    private func waitFor(_ controller: AgentEventsController, _ phase: AgentEventsPhase) async throws {
        for _ in 0..<150 where controller.phase != phase {
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertEqual(controller.phase, phase)
    }
}
#endif
