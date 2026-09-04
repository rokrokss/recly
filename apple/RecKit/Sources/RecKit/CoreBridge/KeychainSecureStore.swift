import Foundation
import ReclyCore
import Security

/// `SecureStore` (docs/01) on the Keychain. One generic-password item per `(ns, key)`: the core's
/// namespaces (`secrets`, `tokens`, plus the shell's own `device`) become the item's service, so
/// two namespaces can hold the same key without colliding.
///
/// The `__`-prefixed names are the raw Kotlin members: SKIE hides them behind the `async` wrappers
/// that callers use, but a Swift *implementation* of the interface still fills in the originals.
/// Every call here is a synchronous `SecItem*` — there is nothing to actually await.
public final class KeychainSecureStore: ReclyCore.SecureStore {
    /// Anything that is not "no such item" — a locked keychain, a denied ACL.
    ///
    /// `CustomNSError` and not just `Error`: this crosses back into Kotlin as an `NSError`, and the
    /// default bridging of a Swift `struct` gives every case the same "error 1" — the one number a
    /// keychain failure is diagnosed by is the `OSStatus`, so it has to be the error's own code.
    public struct Failure: Error, CustomNSError, CustomStringConvertible {
        public let status: OSStatus

        public static var errorDomain: String { "RecKit.KeychainSecureStore.Failure" }
        public var errorCode: Int { Int(status) }
        public var errorUserInfo: [String: Any] { [NSLocalizedDescriptionKey: description] }

        public var description: String {
            "keychain \(status): \(SecCopyErrorMessageString(status, nil) as String? ?? "unknown")"
        }
    }

    private let servicePrefix: String

    public init(servicePrefix: String = CoreBridge.appName) {
        self.servicePrefix = servicePrefix
    }

    public func __get(ns: String, key: String) async throws -> KotlinByteArray? {
        var query = item(ns: ns, key: key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var found: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &found)
        switch status {
        case errSecSuccess:
            return (found as? Data)?.kotlinByteArray
        case errSecItemNotFound:
            return nil
        default:
            throw Failure(status: status)
        }
    }

    public func __put(ns: String, key: String, value: KotlinByteArray) async throws {
        let query = item(ns: ns, key: key)
        let data = value.data

        let updated = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if updated == errSecSuccess { return }
        guard updated == errSecItemNotFound else { throw Failure(status: updated) }

        var insert = query
        insert[kSecValueData as String] = data
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(insert as CFDictionary, nil)
        guard added == errSecSuccess else { throw Failure(status: added) }
    }

    public func __delete(ns: String, key: String) async throws {
        let status = SecItemDelete(item(ns: ns, key: key) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw Failure(status: status) }
    }

    /// Every key stored in [ns]: what `SecureStore.clear` loops over to empty a namespace on
    /// "연결 해제" (docs/03), and what the core's `SecretsRepository` lists the secrets from.
    /// Nothing to await — every call here is a synchronous `SecItem*`.
    ///
    /// Only "no such item" is an empty list. A keychain that will not be *read* — a process with no
    /// keychain-access-group entitlement (`errSecMissingEntitlement`), a locked device
    /// (`errSecInteractionNotAllowed`), an ACL that denied the query — throws like every other
    /// operation here: answering "none" would make `clear` look like it emptied a namespace whose
    /// values are all still on the device, and a backfill look like it had nothing to bring in.
    public func __names(ns: String) async throws -> [String] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "\(servicePrefix).\(ns)",
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var found: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &found)
        switch status {
        case errSecSuccess:
            let items = found as? [[String: Any]] ?? []
            return items.compactMap { $0[kSecAttrAccount as String] as? String }
        case errSecItemNotFound:
            return []
        default:
            throw Failure(status: status)
        }
    }

    private func item(ns: String, key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "\(servicePrefix).\(ns)",
            kSecAttrAccount as String: key,
        ]
    }
}
