import Carbon.HIToolbox
import Foundation

/// docs/12 "Menu bar app": ⌥⌘R starts and stops a recording from any app. A Carbon hot key — the one
/// global shortcut that needs no Accessibility permission — registered exclusively, so a combination
/// another app already holds is refused rather than shared, and the settings row can say so.
@MainActor
final class GlobalShortcut {
    /// What the settings row shows. Fixed: there is no picker.
    static let label = "⌥⌘R"

    private let action: () -> Void
    private var hotKey: EventHotKeyRef?
    private var handler: EventHandlerRef?

    init(action: @escaping () -> Void) {
        self.action = action
    }

    /// Registers the shortcut, or takes it away. False when the system refused it.
    @discardableResult
    func set(enabled: Bool) -> Bool {
        unregister()
        guard enabled else { return true }
        if handler == nil {
            var pressed = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
            InstallEventHandler(
                GetApplicationEventTarget(),
                { _, _, owner in
                    guard let owner else { return OSStatus(eventNotHandledErr) }
                    let shortcut = Unmanaged<GlobalShortcut>.fromOpaque(owner).takeUnretainedValue()
                    // Carbon delivers hot keys on the main thread's event loop.
                    MainActor.assumeIsolated { shortcut.action() }
                    return noErr
                },
                1, &pressed, Unmanaged.passUnretained(self).toOpaque(), &handler
            )
        }
        let id = EventHotKeyID(signature: Self.signature, id: 1)
        let status = RegisterEventHotKey(
            UInt32(kVK_ANSI_R), UInt32(cmdKey | optionKey), id,
            GetApplicationEventTarget(), OptionBits(kEventHotKeyExclusive), &hotKey
        )
        if status != noErr { hotKey = nil }
        return status == noErr
    }

    private func unregister() {
        if let hotKey { UnregisterEventHotKey(hotKey) }
        hotKey = nil
    }

    /// `'RecR'`, this app's hot key signature.
    private static let signature: OSType = 0x5265_6352
}
