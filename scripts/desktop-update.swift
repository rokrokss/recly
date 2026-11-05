// docs/recly.md §12 "Updates" · §14 "Updates": the feed the Mac and Windows apps update from — `update.json` and its
// Ed25519 signature `update.json.sig`, attached to every app release (docs/development.md "App releases").
//
//   swift scripts/desktop-update.swift keygen [<key>]
//   swift scripts/desktop-update.swift sign --tag v<version>-build.<n> [--dmg <file>] [--msi <file>] [--out <dir>] [--key <key>]
//
// `keygen` writes a new private key (default `~/.recly/update-signing.key`) and prints its public half, which the two
// apps carry. `sign` writes `update.json` and `update.json.sig` for the DMG and the MSI of one release; it refuses a
// key whose public half is not the one both apps carry, since a feed they cannot verify updates nobody.
// `--test-base-url <url>` (with `--test-public-key-ok`) is for a local feed only: it points the package URLs there
// and skips that check.
import CryptoKit
import Foundation

let repoRoot = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
let defaultKey = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".recly/update-signing.key")
let embeddedIn = [
    "apple/RecKit/Sources/RecKit/Update/UpdateFeed.swift",
    "windows/app/src/main/kotlin/app/recly/windows/update/UpdateFeed.kt",
]

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("desktop-update: \(message)\n".utf8))
    exit(1)
}

func readKey(_ url: URL) -> Curve25519.Signing.PrivateKey {
    guard let text = try? String(contentsOf: url, encoding: .utf8),
          let raw = Data(base64Encoded: text.trimmingCharacters(in: .whitespacesAndNewlines)),
          let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: raw)
    else { fail("no private key at \(url.path) — `keygen` writes one") }
    return key
}

func sha256(_ url: URL) -> String {
    guard let handle = try? FileHandle(forReadingFrom: url) else { fail("cannot read \(url.path)") }
    var hasher = SHA256()
    while let chunk = try? handle.read(upToCount: 1 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
    return hasher.finalize().map { String(format: "%02x", $0) }.joined()
}

func size(_ url: URL) -> Int {
    guard let size = try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int, size > 0 else {
        fail("\(url.path) is missing or empty")
    }
    return size
}

func firstMatch(_ pattern: String, _ text: String) -> [String]? {
    guard let regex = try? NSRegularExpression(pattern: pattern),
          let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text))
    else { return nil }
    return (0..<match.numberOfRanges).map { String(text[Range(match.range(at: $0), in: text)!]) }
}

var args = Array(CommandLine.arguments.dropFirst())
guard let command = args.first else { fail("usage: keygen [<key>] | sign --tag <tag> [--dmg <file>] [--msi <file>] [--out <dir>] [--key <key>]") }
args.removeFirst()

func option(_ name: String) -> String? {
    guard let index = args.firstIndex(of: name) else { return nil }
    guard index + 1 < args.count else { fail("\(name) needs a value") }
    return args[index + 1]
}

switch command {
case "keygen":
    let url = args.first.map { URL(fileURLWithPath: ($0 as NSString).expandingTildeInPath) } ?? defaultKey
    if FileManager.default.fileExists(atPath: url.path) { fail("\(url.path) exists — a new key would strand every install that trusts the old one") }
    let key = Curve25519.Signing.PrivateKey()
    try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    FileManager.default.createFile(
        atPath: url.path,
        contents: Data((key.rawRepresentation.base64EncodedString() + "\n").utf8),
        attributes: [.posixPermissions: 0o600]
    )
    print("private key: \(url.path) — back it up; without it no release can update the installed apps")
    print("public key:  \(key.publicKey.rawRepresentation.base64EncodedString())")

case "sign":
    guard let tag = option("--tag"), let parts = firstMatch(#"^v(\d+\.\d+\.\d+)-build\.(\d+)$"#, tag) else {
        fail("--tag must be v<version>-build.<n>")
    }
    let version = parts[1], build = parts[2]
    let key = readKey(option("--key").map { URL(fileURLWithPath: ($0 as NSString).expandingTildeInPath) } ?? defaultKey)
    let publicKey = key.publicKey.rawRepresentation.base64EncodedString()
    let testBase = option("--test-base-url")
    if testBase == nil {
        for path in embeddedIn {
            let source = (try? String(contentsOf: repoRoot.appendingPathComponent(path), encoding: .utf8)) ?? ""
            if !source.contains("\"\(publicKey)\"") { fail("\(path) does not carry this key's public half \(publicKey)") }
        }
    } else if !args.contains("--test-public-key-ok") {
        fail("--test-base-url is for a local feed signed with a test key: add --test-public-key-ok")
    }
    let base = testBase ?? "https://github.com/rokrokss/recly/releases/download/\(tag)/"

    var feed: [String: Any] = [
        "schema": 1,
        "version": version,
        "tag": tag,
        "notes": "https://github.com/rokrokss/recly/releases/tag/\(tag)",
    ]
    if let dmg = option("--dmg").map({ URL(fileURLWithPath: $0) }) {
        // Recly-macOS-<version>-<build>.dmg, as `make mac-release` names it after the release is put together.
        guard let name = firstMatch(#"^Recly-macOS-(\d+\.\d+\.\d+)-(\d+)\.dmg$"#, dmg.lastPathComponent),
              name[1] == version, name[2] == build
        else { fail("\(dmg.lastPathComponent) is not Recly-macOS-\(version)-\(build).dmg") }
        feed["macos"] = [
            "build": build,
            "url": base + dmg.lastPathComponent,
            "size": size(dmg),
            "sha256": sha256(dmg),
        ]
    }
    if let msi = option("--msi").map({ URL(fileURLWithPath: $0) }) {
        // Recly-<installerVersion>.msi: the MSI's own version, which is what Windows upgrades by (docs/development.md).
        guard let name = firstMatch(#"^Recly-(\d+\.\d+\.\d+)\.msi$"#, msi.lastPathComponent) else {
            fail("\(msi.lastPathComponent) is not Recly-<installerVersion>.msi")
        }
        feed["windows"] = [
            "installerVersion": name[1],
            "url": base + msi.lastPathComponent,
            "size": size(msi),
            "sha256": sha256(msi),
        ]
    }
    if feed["macos"] == nil && feed["windows"] == nil { fail("give --dmg, --msi or both") }

    let out = URL(fileURLWithPath: option("--out") ?? ".")
    let json = try JSONSerialization.data(withJSONObject: feed, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
    let body = json + Data("\n".utf8)
    // The signature is over these bytes exactly, so the apps verify the file before they parse it.
    let signature = try key.signature(for: body)
    try body.write(to: out.appendingPathComponent("update.json"))
    try Data((signature.base64EncodedString() + "\n").utf8).write(to: out.appendingPathComponent("update.json.sig"))
    print(String(decoding: body, as: UTF8.self), terminator: "")
    print("desktop-update: wrote \(out.appendingPathComponent("update.json").path) and update.json.sig")

default:
    fail("unknown command \(command)")
}
