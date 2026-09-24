// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "WhisperKitBenchmark",
    platforms: [.macOS(.v14)],
    dependencies: [.package(url: "https://github.com/argmaxinc/WhisperKit.git", exact: "1.1.0")],
    targets: [.executableTarget(name: "WhisperKitBenchmark", dependencies: [
        .product(name: "WhisperKit", package: "WhisperKit")
    ])]
)
