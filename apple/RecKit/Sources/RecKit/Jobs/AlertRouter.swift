import Foundation

/// docs/10 "탭하면 고칠 수 있는 화면으로 간다": a notification's tap, held until there is something to
/// take it to.
///
/// A notification is most often opened from a *cold* launch — the device was asleep, the offer is on
/// the Lock Screen, and opening it is what starts the process. Notification Center delivers the
/// response to whatever is its delegate the moment the app finishes launching, which is long before
/// the database is open, the recorder exists or there is a workflow editor. So the delegate is
/// installed from the app's `init` (there is nothing to route to yet, and that is fine) and what
/// arrives before the model can answer it waits here instead of being dropped on the floor.
///
/// [Tap] because both of the app's notifications need this and they carry different things: a
/// [JobAlert] on the phone and the Mac, and — on the Mac — the meeting offer's own action, which
/// cannot be served until there is a recorder to start.
///
/// One pending tap and not a queue: two taps before the app is up are one intent, and the second is
/// the one the user made last.
@MainActor
public final class AlertRouter<Tap> {
    private var pending: Tap?
    private var route: ((Tap) -> Void)?

    public init() {}

    /// A tap. Routed at once when there is somewhere to route it to, and kept until there is.
    public func deliver(_ tap: Tap) {
        guard let route else {
            pending = tap
            return
        }
        route(tap)
    }

    /// The model can answer a tap now. Whatever arrived before this does so immediately, which is
    /// the whole reason the buffer exists.
    public func connect(_ route: @escaping (Tap) -> Void) {
        self.route = route
        guard let tap = pending else { return }
        pending = nil
        route(tap)
    }

    /// Whether a tap is still waiting for a screen. Only the tests ask.
    var isWaiting: Bool { pending != nil }
}
