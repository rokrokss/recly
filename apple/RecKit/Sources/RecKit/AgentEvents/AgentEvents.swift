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

    public struct Drive: Decodable, Equatable, Sendable {
        public var lastError: String?

        public init(lastError: String? = nil) {
            self.lastError = lastError
        }
    }

    public var home: String
    public var tunnelId: String?
    public var tunnelKey: Bool
    public var googleSignedIn: Bool
    /// nil when no server answers on this home.
    public var server: Server?
    public var drive: Drive
    public var subscriptions: Int
    /// There were subscriptions and none is left — the agent unsubscribed, or ChatGPT refused a
    /// delivery with 410. recly-events never expires one itself.
    public var subscriptionsEnded: Bool

    public init(
        home: String, tunnelId: String? = nil, tunnelKey: Bool = false, googleSignedIn: Bool = false,
        server: Server? = nil, drive: Drive = Drive(), subscriptions: Int = 0, subscriptionsEnded: Bool = false
    ) {
        self.home = home
        self.tunnelId = tunnelId
        self.tunnelKey = tunnelKey
        self.googleSignedIn = googleSignedIn
        self.server = server
        self.drive = drive
        self.subscriptions = subscriptions
        self.subscriptionsEnded = subscriptionsEnded
    }

    /// Whether an agent is listening, for the running row.
    public var subscription: AgentEventsSubscription {
        if subscriptions > 0 { return .active }
        return subscriptionsEnded ? .ended : .none
    }

    /// Everything `serve` needs: a Google sign-in, a tunnel and its key.
    public var setUp: Bool {
        googleSignedIn && !(tunnelId ?? "").isEmpty && tunnelKey
    }

    /// Google refused the stored sign-in — a Disconnect in any Recly app revokes it (docs/recly.md
    /// §15 §9) — so polling Drive keeps failing until the user connects again.
    public var googleEnded: Bool {
        guard let error = drive.lastError else { return false }
        return error.contains("invalid_grant") || error.contains("HTTP 401")
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
    case off
    /// `init --google` is waiting for the browser.
    case signingIn
    /// On, but Google or the tunnel is missing.
    case needsSetup
    /// Restarted too often; the user turns it off and on again.
    case gaveUp
    case googleEnded
    /// A server this app did not start answers on the same home — the CLI's service, or a
    /// terminal.
    case elsewhere
    case starting
    case connecting
    case tunnelError
    case running(AgentEventsSubscription)

    public static func of(
        enabled: Bool, available: Bool, signingIn: Bool, gaveUp: Bool, owned: Bool,
        status: AgentEventsStatus?
    ) -> AgentEventsPhase {
        guard available else { return .unavailable }
        guard enabled else { return .off }
        if signingIn { return .signingIn }
        if gaveUp { return .gaveUp }
        guard let status else { return .starting }
        if status.googleEnded { return .googleEnded }
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
    /// Stops when the app's end of its standard input closes — when the app goes, however it goes.
    public static let serve = ["serve", "--exit-with-stdin"]
    public static let signIn = ["init", "--google", "--no-check"]

    /// The key goes in on standard input, never in the arguments, which other processes can read.
    public static func saveTunnel(id: String, hasKey: Bool) -> [String] {
        ["init", "--tunnel-id", id] + (hasKey ? ["--tunnel-key-stdin"] : []) + ["--no-check"]
    }
}

/// docs/12 "Agent connection": keeps `recly-events serve` running while the switch is on, and runs
/// `init` for the settings rows. Polls `status --json` every few seconds while the switch is on.
@MainActor
public final class AgentEventsController: ObservableObject {
    @Published public private(set) var phase: AgentEventsPhase = .off
    /// The saved tunnel ID, for the field's first value.
    @Published public private(set) var tunnelId = ""
    @Published public private(set) var googleSignedIn = false
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

    private let executable: URL?
    private let defaults: UserDefaults
    private let logger = Logger(subsystem: CoreBridge.appName, category: "agent")
    private var child: Process?
    /// The server's standard input; it stops when this end closes ([AgentEventsCommand.serve]).
    private var childInput: Pipe?
    private var signingIn = false
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

    /// `init --google`: the program opens the browser and waits for Google's answer. A server
    /// already running keeps the old sign-in in memory, so it is restarted afterwards.
    public func connectGoogle() {
        guard let executable, !signingIn else { return }
        signingIn = true
        publish()
        Task {
            // Abandoned in the browser, the sign-in would wait for ever.
            let result = await Self.run(executable, AgentEventsCommand.signIn, timeout: 300)
            signingIn = false
            logger.info("agent.signIn exit=\(result.code, privacy: .public)")
            if result.code == 0 { await restartChild() }
            await refresh()
        }
    }

    /// `init --tunnel-id`, with the key on standard input when one was typed.
    public func saveTunnel(id: String, key: String) {
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
        guard enabled || child != nil else { return }
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
            googleSignedIn = status.googleSignedIn
        }
        switch AgentEventsAction.reconcile(enabled: enabled, gaveUp: gaveUp, childRunning: child != nil, status: status) {
        case .start: if let status { startChild(home: status.home) }
        case .stop: stopChild()
        case .none: break
        }
        publish()
    }

    private func publish() {
        phase = AgentEventsPhase.of(
            enabled: enabled, available: executable != nil, signingIn: signingIn, gaveUp: gaveUp,
            owned: status?.server?.pid == child?.processIdentifier, status: status
        )
    }

    // MARK: - The server process

    private func startChild(home: String) {
        guard let executable, child == nil else { return }
        let process = Process()
        process.executableURL = executable
        process.arguments = AgentEventsCommand.serve
        let input = Pipe()
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
