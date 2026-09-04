// swift-tools-version: 5.10
import PackageDescription

// `Frameworks/ReclyCore.xcframework` is a Gradle output staged into the package by
// `apple/scripts/build-core.sh`, not a checked-in artifact (it is gitignored): run that script
// before resolving this package. The path stays inside the package root so `swift build` resolves
// it with no assumption about where the repo is checked out around it.
let package = Package(
    name: "RecKit",
    // docs/07 rule 1: English is the base language and Korean the translation, here as everywhere.
    defaultLocalization: "en",
    // macOS 14.4 rather than 14.0: the Core Audio process tap M4-L3 records system audio with is
    // 14.2 API whose TCC flow only settles in 14.4 (docs/12), and the RecMac target already builds
    // against that floor.
    platforms: [.macOS("14.4"), .iOS(.v17), .watchOS(.v10)],
    products: [
        .library(name: "RecKit", targets: ["RecKit"]),
        // The one in-memory `SecureStore` both test bundles run against. A product and not a file in
        // each of them: a test target's sources are not visible from another target, and the phone's
        // `ReclyTests` is a separate Xcode project (docs/12) that can only reach a *product*.
        .library(name: "RecKitTestSupport", targets: ["RecKitTestSupport"]),
    ],
    dependencies: [
        // docs/06 "iOS · macOS": the SDK keeps the refresh token in the Keychain and renews it with
        // `refreshTokensIfNeeded`, which is the whole of what `AppleTokenProvider` wraps.
        .package(url: "https://github.com/google/GoogleSignIn-iOS.git", from: "9.0.0"),
        // GoogleSignIn's own dependency, named here so the Mac can hand the SDK a keychain store
        // of its choosing (docs/06 "iOS · macOS": the login keychain, because an ad-hoc build has
        // no access group for the data-protection one).
        .package(url: "https://github.com/google/GTMAppAuth.git", from: "5.0.0"),
    ],
    targets: [
        .binaryTarget(
            name: "ReclyCore",
            path: "Frameworks/ReclyCore.xcframework"
        ),
        .target(
            name: "RecKit",
            dependencies: [
                "ReclyCore",
                // Conditional: GoogleSignIn has no watchOS slice, and the watch never touches Drive
                // (ADR-002) — the phone signs in for it.
                .product(
                    name: "GoogleSignIn",
                    package: "GoogleSignIn-iOS",
                    condition: .when(platforms: [.macOS, .iOS])
                ),
                .product(
                    name: "GTMAppAuth",
                    package: "GTMAppAuth",
                    condition: .when(platforms: [.macOS])
                ),
            ],
            path: "Sources/RecKit",
            // The shared catalog (docs/07): RecKit hands the shells keys, and this is where the
            // sentences they resolve to live.
            resources: [.process("Resources")]
        ),
        // No XCTest of its own: it is a fake the bundles hand the core, and a target that linked
        // XCTest could not be linked into anything but a test bundle.
        .target(
            name: "RecKitTestSupport",
            dependencies: ["ReclyCore"],
            path: "Sources/RecKitTestSupport"
        ),
        // The static XCFramework carries no `LC_LINKER_OPTION`, so every *linked product* has to
        // name sqlite itself (docs/12·13). For the app that is the RecMac target's Other Linker
        // Flags; here it is the xctest bundle.
        .testTarget(
            name: "RecKitTests",
            dependencies: ["RecKit", "RecKitTestSupport"],
            path: "Tests/RecKitTests",
            linkerSettings: [.linkedLibrary("sqlite3")]
        ),
    ]
)
