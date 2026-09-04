import Foundation
import ReclyCore

/// The shell's half of docs/01 "the core ↔ shell boundary", assembled once and held for the life of the
/// process. Nothing above this type constructs a `CoreDeps` or opens the database itself.
///
/// The Kotlin class and the binary framework are both called `ReclyCore`; SKIE breaks the tie by
/// exporting the class to Swift as `ReclyCore_`.
public struct CoreBridge {
    public let core: ReclyCore_
    public let deps: CoreDeps
    /// Where `okio` writes: parts, `meta.json`, the SQLite file's directory.
    public let dataDirectory: URL

    /// docs/12 M1: open the local DB and read the workflow list. Audio capture, sign-in and the
    /// executor arrive in later lanes, so the only shell pieces wired here are the six of
    /// `CoreDeps` plus the driver factory.
    ///
    /// Nothing here touches the Keychain. The store is handed on to the core for the things that
    /// are actually secret (`tokens`, `secrets`), and it is first reached when one of those is
    /// wanted — never on the way to the menu (see [deviceId]).
    public static func make(
        appVersion: String,
        platform: Platform = CoreBridge.defaultPlatform,
        deviceName: String = CoreBridge.deviceName,
        dataDirectory: URL = CoreBridge.defaultDataDirectory,
        databaseName: String = "rec.db",
        clock: any ReclyCore.Clock = SystemClock(),
        logger: any ReclyCore.Logger = OSLogLogger(),
        secureStore: any ReclyCore.SecureStore = KeychainSecureStore(),
        tokenProvider: any ReclyCore.TokenProvider = StubTokenProvider(),
        /// ADR-015: the phone hands in a [BackgroundTransport] so the chunk PUTs survive the app
        /// being suspended (docs/13 I4). Nil is the Ktor transport every other shell uses.
        transport: (any ReclyCore.Transport)? = nil,
        /// docs/08: the remux a `transcribe` step needs. The same one on the Mac and the phone.
        audio: any ReclyCore.AudioTools = AppleAudioTools()
    ) async throws -> CoreBridge {
        try FileManager.default.createDirectory(at: dataDirectory, withIntermediateDirectories: true)
        try relocateLegacyDatabase(named: databaseName, into: dataDirectory, logger: logger)

        let deps = CoreDeps(
            clock: clock,
            logger: logger,
            secureStore: secureStore,
            tokenProvider: tokenProvider,
            transport: transport ?? CoreBridge.ktorTransport(),
            fileSystem: OkioFileSystem.companion.SYSTEM,
            audio: audio,
            dataDir: OkioPath.companion.toPath(dataDirectory.path, normalize: true),
            device: DeviceInfo(
                deviceId: try deviceId(in: dataDirectory),
                platform: platform,
                name: deviceName
            ),
            appVersion: appVersion,
            io: AppleRuntime.shared.ioDispatcher(),
            // docs/07 §6: the seed names are the device's language from the moment they are
            // written, so they are decided here and not left to the English base — the shells'
            // own strings follow in I18N-L2.
            locale: CoreBridge.deviceLanguage
        )

        return CoreBridge(
            // `basePath`, so the SQLite file lands in `dataDir` with everything else the app owns.
            // `openCore` rather than `ReclyCore_(deps:driverFactory:)`: the database is opened in the
            // initialiser, and a Kotlin initialiser that throws aborts the process — this one is
            // `@Throws`, so a database that will not open reaches the caller as an error.
            core: try AppleRuntime.shared.openCore(
                deps: deps,
                name: databaseName,
                basePath: dataDirectory.path
            ),
            deps: deps,
            dataDirectory: dataDirectory
        )
    }

    /// Builds before this one let the native driver pick the directory, which put the database in
    /// sqliter's default `~/Library/Application Support/databases/`. Move it across the first time
    /// we find it, so an early install keeps its recordings.
    private static func relocateLegacyDatabase(
        named name: String,
        into dataDirectory: URL,
        logger: any ReclyCore.Logger
    ) throws {
        let manager = FileManager.default
        guard let support = manager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        else { return }
        try relocateLegacyDatabase(
            named: name,
            from: support.appendingPathComponent("databases", isDirectory: true),
            into: dataDirectory,
            logger: logger
        )
    }

    /// The move itself, with both directories named so a test can hand it two of its own.
    ///
    /// The database and the `-wal`/`-shm` it is worthless without are **one unit**: all of them
    /// move or none does. Two rules follow. A database already sitting at the destination ends it
    /// — those sidecars belong to *that* file, and dropping a foreign pair beside it is how a
    /// working database gets corrupted — so nothing moves, not even the sidecars on their own.
    /// And sidecars in the legacy directory with no database next to them are leftovers of a move
    /// that already happened; they stay where they are.
    ///
    /// A move that fails part-way puts back what it took (best effort) and rethrows rather than
    /// carrying on: the legacy set stays whole and the destination stays empty, so the next launch
    /// tries again. Swallowing it would open a fresh database at the destination instead, and the
    /// rule above would then skip the legacy one forever.
    ///
    /// Nothing to do once the source is gone, which is every launch after the first.
    static func relocateLegacyDatabase(
        named name: String,
        from legacyDirectory: URL,
        into dataDirectory: URL,
        logger: any ReclyCore.Logger
    ) throws {
        let manager = FileManager.default
        let present = databaseSuffixes.filter {
            manager.fileExists(atPath: legacyDirectory.appendingPathComponent(name + $0).path)
        }
        guard !present.isEmpty else { return }

        // `present` keeps `databaseSuffixes` order, so `first == ""` is "the database itself is
        // there" — and moving it first means a rollback only ever has whole files to put back.
        guard present.first == "",
              !manager.fileExists(atPath: dataDirectory.appendingPathComponent(name).path)
        else {
            logger.log(
                level: LoggerLevel.info,
                event: "db.relocate.skipped",
                fields: ["name": name, "legacyFiles": present.count],
                error: nil
            )
            return
        }

        var moved: [String] = []
        do {
            for suffix in present {
                try manager.moveItem(
                    at: legacyDirectory.appendingPathComponent(name + suffix),
                    to: dataDirectory.appendingPathComponent(name + suffix)
                )
                moved.append(suffix)
            }
        } catch {
            for suffix in moved {
                try? manager.moveItem(
                    at: dataDirectory.appendingPathComponent(name + suffix),
                    to: legacyDirectory.appendingPathComponent(name + suffix)
                )
            }
            logger.log(
                level: LoggerLevel.error,
                event: "db.relocate.failed",
                fields: ["name": name, "movedBack": moved.count],
                error: nil
            )
            throw error
        }
        logger.log(
            level: LoggerLevel.info,
            event: "db.relocate.ok",
            fields: ["name": name, "files": moved.count],
            error: nil
        )
    }

    /// The database file and the two SQLite writes beside it, in the order they have to move.
    private static let databaseSuffixes = ["", "-wal", "-shm"]

    /// The two docs/05 defaults are seeded on first read, so this is never empty.
    /// A `suspend fun` called as Swift `async` — SKIE's doing (see `docs/measurements.md`).
    public func workflowNames() async throws -> [String] {
        try await core.workflows.current().workflows.map(\.name)
    }

    /// docs/01: a UUID v4 minted at install time; a reinstall gets a new one.
    ///
    /// A plain file next to the database, not the Keychain. The id is not a secret — it says which
    /// install a recording came from, and it is written into every `meta.json` in the clear — and
    /// putting it behind `SecItemCopyMatching` cost the app its launch: on an ad-hoc-signed build
    /// the code signature changes with every rebuild, the ACL the previous build was given no
    /// longer matches, and the legacy file keychain answers the read with a modal that the menu
    /// never gets past (measured, see docs/measurements.md). Anything that *is* secret still goes
    /// to `KeychainSecureStore` — the core's `tokens` and `secrets` namespaces are untouched.
    ///
    /// An install that already has an id in the Keychain mints a new one here. That is the same
    /// thing a reinstall does, which docs/01 already allows for, and reading the old one would mean
    /// making exactly the Keychain call this is here to avoid.
    static func deviceId(in dataDirectory: URL) throws -> String {
        let url = dataDirectory.appendingPathComponent(deviceIdFile)
        if let stored = try? Data(contentsOf: url),
           let existing = String(data: stored, encoding: .utf8)?
               .trimmingCharacters(in: .whitespacesAndNewlines),
           !existing.isEmpty {
            return existing
        }
        let minted = UUID().uuidString
        // Atomic: a half-written id is one the next launch would read back as a different device.
        try Data(minted.utf8).write(to: url, options: .atomic)
        return minted
    }

    static let deviceIdFile = "device.id"

    /// The default transport, and the one a [BackgroundTransport] falls back to for everything that
    /// is not a chunk PUT — a Kotlin implementation, so SKIE's `async` wrapper is the right way to
    /// call it.
    public static func ktorTransport() -> KtorTransport {
        KtorTransport(
            client: AppleRuntime.shared.httpClient(),
            fileSystem: OkioFileSystem.companion.SYSTEM
        )
    }
}

public extension CoreBridge {
    /// The one name this install is keyed by, in all three places that need one: the Application
    /// Support directory below, the Keychain service prefix and the log subsystem. Not the bundle
    /// id — the watch app's is `app.recly.watchkitapp` — but per platform for the same reason, so
    /// nothing on a Mac ever reads what a phone wrote.
    static var appName: String {
        #if os(macOS)
        "app.recly.mac"
        #elseif os(watchOS)
        "app.recly.watch"
        #else
        "app.recly"
        #endif
    }

    /// Unsandboxed on macOS (docs/12: direct distribution) and inside the app container on
    /// iOS·watchOS (docs/13 deliverable 3: no app group) — Application Support is ours to write
    /// either way.
    /// It does not exist yet on a fresh iOS install; `make` creates it.
    static var defaultDataDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent(appName, isDirectory: true)
    }

    /// What every `meta.json` this install writes says produced it (docs/01 `DeviceInfo`).
    static var defaultPlatform: Platform {
        #if os(macOS)
        .macos
        #elseif os(watchOS)
        .watchos
        #else
        .ios
        #endif
    }

    static var deviceName: String {
        #if os(macOS)
        Host.current().localizedName ?? ProcessInfo.processInfo.hostName
        #else
        ProcessInfo.processInfo.hostName
        #endif
    }

    /// The language the core seeds default workflow names in (docs/07 §6), as the bare tag the
    /// core understands. The *app's* language rather than the device's, because that is what the
    /// user is reading when the seed is written; `en` and `ko` are the two the app has (docs/07
    /// §1) and anything else falls back to the English base.
    static var deviceLanguage: String { AppLanguage.resolvedCode }

    /// What this build is, for [make] and for the About block at the bottom of every settings
    /// screen (docs/09 트렌드 6). The three models each carried a copy of it.
    static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0"
    }

    /// The other two halves of that same About line — the build number behind [appVersion], and the
    /// OS this copy is running on. Here rather than in each settings screen for the reason above:
    /// the Mac's and the phone's carried the same two accessors word for word.
    static var appBuild: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
    }

    /// Without the "Version " `ProcessInfo` puts in front: the line already says which OS it is.
    static var systemVersion: String {
        ProcessInfo.processInfo.operatingSystemVersionString
            .replacingOccurrences(of: "Version ", with: "")
    }

    /// ADR-006's 900 seconds unless a shorter one was handed in on the command line
    /// (`xcrun simctl launch … -segmentSec 20`, which lands in `NSArgumentDomain`) — the smoke runs
    /// of docs/lanes M5-L2·M5-L4 need several boundaries in a couple of minutes, and RecMac's
    /// `voiceProcessing` is the same kind of unlisted default.
    ///
    /// It is read as a default argument of the models' initialisers, so it belongs to no actor —
    /// which a plain `struct` extension already is.
    static var configuredSegmentSec: Int {
        let configured = UserDefaults.standard.integer(forKey: "segmentSec")
        return configured > 0 ? configured : SegmentedRecorder.defaultSegmentSec
    }
}
