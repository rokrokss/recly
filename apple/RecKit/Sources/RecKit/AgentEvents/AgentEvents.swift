#if os(macOS)
import Foundation
import os

/// docs/12 "Agent connection": what `recly-events status --json` says (events/, docs/recly.md §15
/// §9). The Mac app talks to recly-events only by running it — `status --json`, `init`, `serve` —
/// so this is everything it knows about it.
public struct AgentEventsStatus: Decodable, Equatable, Sendable {
    public struct Server: Decodable, Equatable, Sendable {
        public var pid: Int32
        public var tunnelReady: Bool
        public var tunnelError: String?

        public init(pid: Int32, tunnelReady: Bool, tunnelError: String?) {
            self.pid = pid
            self.tunnelReady = tunnelReady
            self.tunnelError = tunnelError
        }
    }

    public var home: String
    public var tunnelId: String?
    public var tunnelKey: Bool
    /// nil when no server answers on this home.
    public var server: Server?
    public var subscriptions: Int
    /// There were subscriptions and none is left — the agent unsubscribed, or ChatGPT refused a
    /// delivery with 410. recly-events never expires one itself.
    public var subscriptionsEnded: Bool

    public init(
        home: String, tunnelId: String? = nil, tunnelKey: Bool = false, server: Server? = nil,
        subscriptions: Int = 0, subscriptionsEnded: Bool = false
    ) {
        self.home = home
        self.tunnelId = tunnelId
        self.tunnelKey = tunnelKey
        self.server = server
        self.subscriptions = subscriptions
        self.subscriptionsEnded = subscriptionsEnded
    }

    /// Whether an agent is listening, for the running row.
    public var subscription: AgentEventsSubscription {
        if subscriptions > 0 { return .active }
        return subscriptionsEnded ? .ended : .none
    }

    /// What `serve` needs from its own home: a tunnel and its key. Drive is this app's own
    /// connection, handed over on standard input ([AgentEventsCommand.serve]).
    public var setUp: Bool {
        !(tunnelId ?? "").isEmpty && tunnelKey
    }
}

/// Whether an agent listens: one subscribed, none ever did, or one did and stopped — which only
/// the agent can undo, by subscribing again.
public enum AgentEventsSubscription: Equatable, Sendable {
    case active
    case none
    case ended
}

/// What the settings row says. One case per sentence the row can show.
public enum AgentEventsPhase: Equatable, Sendable {
    /// This build has no recly-events in it.
    case unavailable
    /// Recordings go to iCloud or a local folder, where recly-events sees nothing.
    case notDrive
    case off
    /// On, but this device's Google Drive is not connected: the server runs on that connection.
    case needsDrive
    /// On, but the tunnel or its key is missing.
    case needsSetup
    /// Restarted too often; the user turns it off and on again.
    case gaveUp
    /// A server this app did not start answers on the same home — the CLI's service, or a
    /// terminal.
    case elsewhere
    case starting
    case connecting
    case tunnelError
    case running(AgentEventsSubscription)

    /// - Parameters:
    ///   - driveStorage: whether recordings go to Google Drive; nil until the app has read it.
    ///   - driveConnected: whether this device's Drive is connected; nil until the app can say.
    public static func of(
        enabled: Bool, available: Bool, driveStorage: Bool? = true, driveConnected: Bool? = true, gaveUp: Bool,
        owned: Bool, status: AgentEventsStatus?
    ) -> AgentEventsPhase {
        guard available else { return .unavailable }
        if driveStorage == false { return .notDrive }
        guard enabled else { return .off }
        if gaveUp { return .gaveUp }
        guard driveStorage != nil, driveConnected != nil, let status else { return .starting }
        if driveConnected == false { return .needsDrive }
        guard status.setUp else { return .needsSetup }
        guard let server = status.server else { return .starting }
        if !owned { return .elsewhere }
        if let error = server.tunnelError, !error.isEmpty { return .tunnelError }
        if !server.tunnelReady { return .connecting }
        return .running(status.subscription)
    }
}

/// What to do about the `serve` this app runs, given what the last `status --json` said.
public enum AgentEventsAction: Equatable, Sendable {
    case none
    case start
    case stop

    public static func reconcile(
        enabled: Bool, gaveUp: Bool, childRunning: Bool, status: AgentEventsStatus?
    ) -> AgentEventsAction {
        guard enabled, !gaveUp else { return childRunning ? .stop : .none }
        guard !childRunning, let status, status.setUp, status.server == nil else { return .none }
        return .start
    }
}

/// At most three restarts in ten minutes, as the Windows app allows its capture helper: a server
/// that keeps dying is reported, not respawned forever.
public struct AgentEventsRestarts: Sendable {
    public static let limit = 3
    public static let window: TimeInterval = 600
    private var times: [Date] = []

    public init() {}

    /// Records a restart at `now`; false when the budget is spent.
    public mutating func allow(at now: Date) -> Bool {
        times.removeAll { now.timeIntervalSince($0) > Self.window }
        guard times.count < Self.limit else { return false }
        times.append(now)
        return true
    }

    public mutating func reset() {
        times.removeAll()
    }
}

/// The commands the app runs, in one place.
public enum AgentEventsCommand {
    public static let status = ["status", "--json"]
    /// Takes this app's Drive access token on standard input, one per line, in place of a Google
    /// sign-in of its own, and stops when the app's end closes — when the app goes, however it goes.
    public static let serve = ["serve", "--drive-token-stdin"]

    /// The key goes in on standard input, never in the arguments, which other processes can read.
    public static func saveTunnel(id: String, hasKey: Bool) -> [String] {
        ["init", "--tunnel-id", id] + (hasKey ? ["--tunnel-key-stdin"] : []) + ["--no-check"]
    }
}

/// docs/12 "Agent connection": keeps `recly-events serve` running while the switch is on, on this
/// device's own Drive connection, and runs `init` for the tunnel row. Polls `status --json` every few
/// seconds while the switch is on.
@MainActor
public final class AgentEventsController: ObservableObject {
    @Published public private(set) var phase: AgentEventsPhase = .off
    /// The saved tunnel ID, for the field's first value.
    @Published public private(set) var tunnelId = ""
    /// A tunnel key is saved; the key itself is never read back.
    @Published public private(set) var tunnelKeySaved = false
    /// The last tunnel save failed (a bad ID, no key).
    @Published public private(set) var saveFailed = false

    public var enabled: Bool {
        didSet {
            guard enabled != oldValue else { return }
            defaults.set(enabled, forKey: Self.enabledKey)
            gaveUp = false
            restarts.reset()
            logger.info("agent.enabled value=\(self.enabled, privacy: .public)")
            Task { await refresh() }
        }
    }

    public static let enabledKey = "agentEventsEnabled"

    /// Whether recordings go to Google Drive, the only storage recly-events can watch; nil until the
    /// app has read it, and nothing is started before then.
    public var driveStorage: Bool? {
        didSet {
            guard driveStorage != oldValue else { return }
            logger.info("agent.storage drive=\(String(describing: self.driveStorage), privacy: .public)")
            Task { await refresh() }
        }
    }

    /// Whether this device's Drive is connected, as the app's own Drive row says; the server runs on
    /// that connection and is not started without it.
    public var driveConnected: (() -> Bool)?

    /// This device's current Drive access token, nil when there is none to give right now. It goes
    /// to the server on its standard input and nowhere else (docs/recly.md §15 §9).
    public var driveToken: (() async -> String?)?

    private let executable: URL?
    private let defaults: UserDefaults
    private let logger = Logger(subsystem: CoreBridge.appName, category: "agent")
    private var child: Process?
    /// The server's standard input; it stops when this end closes ([AgentEventsCommand.serve]).
    private var childInput: Pipe?
    /// The token last written to [childInput], so a token is written once.
    private var givenToken: String?
    private var gaveUp = false
    private var restarts = AgentEventsRestarts()
    private var status: AgentEventsStatus?
    private var timer: Timer?

    /// - Parameter executable: the bundled recly-events, or nil when this build has none.
    public init(executable: URL?, defaults: UserDefaults = .standard) {
        self.executable = executable
        self.defaults = defaults
        self.enabled = defaults.bool(forKey: Self.enabledKey)
        self.phase = executable == nil ? .unavailable : .off
        let timer = Timer(timeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.tick() }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
        // Off means the program is not run at all.
        if enabled {
            Task { await refresh() }
        }
    }

    // MARK: - Settings actions

    /// `init --tunnel-id`, with the key on standard input when one was typed — without one, the
    /// saved key stays. `onSaved` runs once the program has taken it.
    public func saveTunnel(id: String, key: String, onSaved: (() -> Void)? = nil) {
        guard let executable else { return }
        let id = id.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = key.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            let result = await Self.run(
                executable, AgentEventsCommand.saveTunnel(id: id, hasKey: !key.isEmpty),
                input: key.isEmpty ? nil : Data(key.utf8)
            )
            saveFailed = result.code != 0
            logger.info("agent.tunnel.save exit=\(result.code, privacy: .public)")
            if result.code == 0 {
                gaveUp = false
                restarts.reset()
                onSaved?()
                await restartChild()
            }
            await refresh()
        }
    }

    /// Looks again now rather than at the next tick. A menu bar app out of sight is napped and its
    /// timer can run minutes late, so the section asks when it is shown.
    public func refreshNow() {
        Task { await refresh() }
    }

    /// The app is quitting: the server goes with it.
    public func shutdown() {
        timer?.invalidate()
        timer = nil
        stopChild()
    }

    // MARK: - Reconciling

    private func tick() async {
        guard (enabled && driveStorage == true && driveConnected?() == true) || child != nil else { return }
        await refresh()
    }

    private func refresh() async {
        guard let executable else {
            publish()
            return
        }
        let result = await Self.run(executable, AgentEventsCommand.status)
        status = result.code == 0 ? try? JSONDecoder().decode(AgentEventsStatus.self, from: result.output) : nil
        if let status {
            tunnelId = status.tunnelId ?? ""
            tunnelKeySaved = status.tunnelKey
        }
        let on = enabled && driveStorage == true && driveConnected?() == true
        switch AgentEventsAction.reconcile(enabled: on, gaveUp: gaveUp, childRunning: child != nil, status: status) {
        case .start: if let status { startChild(home: status.home) }
        case .stop: stopChild()
        case .none: break
        }
        await giveToken()
        publish()
    }

    private func publish() {
        phase = AgentEventsPhase.of(
            enabled: enabled, available: executable != nil, driveStorage: driveStorage,
            driveConnected: driveConnected?(), gaveUp: gaveUp,
            owned: status?.server?.pid == child?.processIdentifier, status: status
        )
    }

    /// The server's Drive access is this device's: its current token, written when it is new — at
    /// the start, and after each refresh the app makes. Never logged. A server that has just died
    /// fails the write rather than taking the app with it (`F_SETNOSIGPIPE` on the pipe).
    private func giveToken() async {
        guard child != nil, let driveToken, let token = await driveToken(), token != givenToken,
              let input = childInput
        else { return }
        do {
            try input.fileHandleForWriting.write(contentsOf: Data((token + "\n").utf8))
            givenToken = token
        } catch {
            logger.error("agent.token.failed")
        }
    }

    // MARK: - The server process

    private func startChild(home: String) {
        guard let executable, child == nil else { return }
        let process = Process()
        process.executableURL = executable
        process.arguments = AgentEventsCommand.serve
        let input = Pipe()
        // A write to a server that has died is an error to handle, not a signal that ends the app.
        _ = fcntl(input.fileHandleForWriting.fileDescriptor, F_SETNOSIGPIPE, 1)
        process.standardInput = input
        // The service's own log file (events/README.md "Everyday use"), so one place has it all.
        let logs = URL(fileURLWithPath: home).appendingPathComponent("logs")
        try? FileManager.default.createDirectory(at: logs, withIntermediateDirectories: true)
        let file = logs.appendingPathComponent("serve.log")
        if !FileManager.default.fileExists(atPath: file.path) {
            FileManager.default.createFile(atPath: file.path, contents: nil)
        }
        if let handle = try? FileHandle(forWritingTo: file) {
            handle.seekToEndOfFile()
            process.standardOutput = handle
            process.standardError = handle
        }
        process.terminationHandler = { [weak self] ended in
            Task { @MainActor in self?.childEnded(ended) }
        }
        do {
            try process.run()
            child = process
            childInput = input
            givenToken = nil
            logger.info("agent.serve.start")
        } catch {
            logger.error("agent.serve.failed error=\(String(describing: error), privacy: .public)")
            gaveUp = !restarts.allow(at: Date())
        }
    }

    private func childEnded(_ process: Process) {
        guard process === child else { return }
        child = nil
        childInput = nil
        logger.info("agent.serve.exit code=\(process.terminationStatus, privacy: .public)")
        if enabled, !restarts.allow(at: Date()) {
            gaveUp = true
            logger.error("agent.serve.gaveUp")
        }
        Task { await refresh() }
    }

    private func stopChild() {
        guard let process = child else { return }
        child = nil
        childInput = nil
        process.terminationHandler = nil
        // SIGTERM: `serve` stops its tunnel and closes its socket on the way out.
        process.terminate()
        logger.info("agent.serve.stop")
    }

    /// Stops the server and waits for it to go, so that the next refresh starts a new one rather
    /// than seeing the old one still answering.
    private func restartChild() async {
        guard let process = child else { return }
        stopChild()
        await Task.detached { process.waitUntilExit() }.value
    }

    // MARK: - Running the program

    struct Result {
        let code: Int32
        let output: Data
    }

    /// Runs one command to the end, off the main actor; `timeout` stops it if it takes longer.
    static func run(
        _ executable: URL, _ arguments: [String], input: Data? = nil, timeout: TimeInterval? = nil
    ) async -> Result {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .utility).async {
                let process = Process()
                process.executableURL = executable
                process.arguments = arguments
                let out = Pipe()
                process.standardOutput = out
                process.standardError = FileHandle.nullDevice
                let stdin = Pipe()
                process.standardInput = stdin
                do {
                    try process.run()
                } catch {
                    continuation.resume(returning: Result(code: -1, output: Data()))
                    return
                }
                if let timeout {
                    DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                        if process.isRunning { process.terminate() }
                    }
                }
                if let input { stdin.fileHandleForWriting.write(input) }
                try? stdin.fileHandleForWriting.close()
                let data = out.fileHandleForReading.readDataToEndOfFile()
                process.waitUntilExit()
                continuation.resume(returning: Result(code: process.terminationStatus, output: data))
            }
        }
    }
}
#endif
