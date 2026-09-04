import Foundation
import ReclyCore

/// The `SecureStore` the tests hand the core, so that nothing in them reaches the real one.
///
/// The Keychain is checked by hand (see docs/measurements.md): it prompts, it is machine state, and
/// a headless `xcodebuild test` has no unlocked login keychain to write to. `xctest` is not the app
/// either — its host has no keychain-access-group entitlement, so every `SecItem*` such a process
/// makes comes back `errSecMissingEntitlement`, and the core is right to treat a store that will not
/// answer as unreadable rather than empty (a listing read as "none" would make a disconnect look
/// like it emptied a namespace still full of keys). What the tests can pin down is the protocol
/// contract, so they run it against this.
///
/// Its own target rather than a file in each bundle: RecKit's tests and the phone's both need one,
/// and a test target's sources are not visible from another one.
///
/// The values are kept as the Kotlin arrays they arrive as — nothing in either bundle looks inside
/// one through this store — so no byte conversion is needed, and `RecKit`'s own conversions are
/// `internal` and out of reach from here anyway.
public final class InMemorySecureStore: ReclyCore.SecureStore {
    private let lock = NSLock()
    private var items: [String: KotlinByteArray] = [:]
    private var recorded: [String] = []

    public init() {}

    /// Every call made, in order. On the real store each one of these is a `SecItem*` — and on an
    /// ad-hoc-signed build, one that can put a modal on screen — so "was this reached at all, and
    /// when" is itself part of the contract (see `CoreBridgeTests`).
    public var calls: [String] { lock.withLock { recorded } }

    public func __get(ns: String, key: String) async throws -> KotlinByteArray? {
        lock.withLock {
            recorded.append("get \(ns)/\(key)")
            return items["\(ns)/\(key)"]
        }
    }

    public func __put(ns: String, key: String, value: KotlinByteArray) async throws {
        lock.withLock {
            recorded.append("put \(ns)/\(key)")
            items["\(ns)/\(key)"] = value
        }
    }

    public func __delete(ns: String, key: String) async throws {
        lock.withLock {
            recorded.append("delete \(ns)/\(key)")
            items.removeValue(forKey: "\(ns)/\(key)")
        }
    }

    public func __names(ns: String) async throws -> [String] {
        lock.withLock {
            recorded.append("names \(ns)")
            return items.keys.filter { $0.hasPrefix("\(ns)/") }.map { String($0.dropFirst(ns.count + 1)) }
        }
    }
}
