#if os(macOS)
import Foundation
import os

/// docs/12 "Updates": the Mac's update check and install, for the settings' Updates section and the popover's line.
///
/// It looks at launch (after a short wait, so it never competes with opening the core) and every six hours while
/// "Check for updates automatically" is on, and whenever the user asks. That switch is off until the user has answered
/// the question the popover asks once ([asks]): the check is a new way for something to leave the Mac, so it is not
/// made on its own before the user has said yes. Nothing is downloaded or installed until the user chooses Restart and
/// install, and never while a recording is being made or saved — the shell says when it may. An automatic check that
/// fails says nothing: the next one, or the user's own, will.
@MainActor
public final class DesktopUpdater: ObservableObject {
    public enum Status: Equatable {
        case idle
        case checking
        case upToDate
        case available(AvailableUpdate)
        case installing(AvailableUpdate)
        /// [update] is what an install was for — retrying installs it again; nil is a check that failed.
        case failed(UpdateFailure, update: AvailableUpdate?)
    }

    /// The one updater of the Mac app. Nil in a Debug build unless a test feed is given (see [fromEnvironment]):
    /// a development build would otherwise offer to put the release in its place.
    public static let shared: DesktopUpdater? = fromEnvironment()

    @Published public private(set) var status: Status = .idle
    /// The user's answer to "Check for updates automatically?" — the popover's question or the settings' switch — and
    /// nil until there is one.
    @Published public private(set) var automatic: Bool?

    /// The settings' switch: off until the user has answered, and setting it is an answer.
    public var checksAutomatically: Bool {
        get { automatic == true }
        set { answer(newValue) }
    }

    /// The popover asks once whether to check on its own.
    public var asks: Bool { automatic == nil }

    public func answer(_ yes: Bool) {
        guard automatic != yes else { return }
        automatic = yes
        defaults.set(yes, forKey: Self.automaticKey)
        logger.info("shell.update.automatic on=\(yes, privacy: .public)")
        schedule()
    }

    /// The update the popover offers: one that is there, being installed, or whose install failed.
    public var offered: AvailableUpdate? {
        switch status {
        case .available(let update), .installing(let update): update
        case .failed(_, let update): update
        default: nil
        }
    }

    private let feed: UpdateFeed
    private let installer: UpdateInstaller
    private let defaults: UserDefaults
    private let firstCheckDelay: Duration
    private var loop: Task<Void, Never>?
    private let logger = Logger(subsystem: "app.recly.mac", category: "shell")
    static let automaticKey = "updates.checkAutomatically"
    static let interval: Duration = .seconds(6 * 60 * 60)

    public init(
        feed: UpdateFeed = UpdateFeed(),
        installer: UpdateInstaller = UpdateInstaller(),
        defaults: UserDefaults = .standard,
        firstCheckDelay: Duration = .seconds(10)
    ) {
        self.feed = feed
        self.installer = installer
        self.defaults = defaults
        self.firstCheckDelay = firstCheckDelay
        automatic = defaults.object(forKey: Self.automaticKey) as? Bool
    }

    private static func fromEnvironment() -> DesktopUpdater? {
        #if DEBUG
        // A local feed signed with a test key — how the swap is tried out without a release (docs/development.md).
        let environment = ProcessInfo.processInfo.environment
        guard let feed = environment["RECLY_UPDATE_FEED"].flatMap(URL.init(string:)),
              let key = environment["RECLY_UPDATE_PUBLIC_KEY"]
        else { return nil }
        return DesktopUpdater(feed: UpdateFeed(feed: feed, publicKey: key))
        #else
        return DesktopUpdater()
        #endif
    }

    /// Starts the automatic checks, once, when the shell is up.
    public func start() {
        if loop == nil { schedule() }
    }

    private func schedule() {
        loop?.cancel()
        loop = nil
        guard checksAutomatically else { return }
        let delay = firstCheckDelay
        loop = Task { [weak self] in
            try? await Task.sleep(for: delay)
            while !Task.isCancelled {
                await self?.check(manual: false)
                try? await Task.sleep(for: Self.interval)
            }
        }
    }

    /// Asks the feed. An automatic check leaves an offered update and an install alone, and fails silently.
    public func check(manual: Bool = true) async {
        if case .checking = status { return }
        if case .installing = status { return }
        if !manual, offered != nil { return }
        let before = status
        status = .checking
        do {
            if let update = try await feed.check(currentBuild: installer.currentBuild) {
                logger.info("shell.update.check outcome=available build=\(update.build, privacy: .public)")
                status = .available(update)
            } else {
                logger.info("shell.update.check outcome=up_to_date")
                status = .upToDate
            }
        } catch {
            let failure = error as? UpdateFailure ?? .check
            logger.error("shell.update.check outcome=failed reason=\(failure.rawValue, privacy: .public)")
            status = manual ? .failed(failure, update: nil) : before
        }
    }

    /// Restart and install: download, check, swap, then quit through [terminate] and open the new app. Refused unless
    /// [idle] — the shell's own word that no recording is being made or saved.
    public func install(idle: Bool, terminate: @escaping @MainActor () -> Void) {
        guard idle, let update = offered else { return }
        if case .installing = status { return }
        let installer = installer
        guard installer.canReplace else {
            logger.error("shell.update.fail phase=prepare reason=\(UpdateFailure.location.rawValue, privacy: .public)")
            status = .failed(.location, update: update)
            return
        }
        logger.info("shell.update.install build=\(update.build, privacy: .public)")
        status = .installing(update)
        Task {
            do {
                let dmg = try await installer.download(update)
                // hdiutil and ditto run to their end; off the main actor.
                try await Task.detached(priority: .userInitiated) {
                    let staged = try installer.stage(dmg: dmg)
                    try installer.replace(with: staged)
                    try? FileManager.default.removeItem(at: dmg)
                }.value
                try installer.relaunch()
                terminate()
            } catch {
                let failure = error as? UpdateFailure ?? .install
                logger.error("shell.update.fail phase=install reason=\(failure.rawValue, privacy: .public)")
                status = .failed(failure, update: update)
            }
        }
    }
}
#endif
