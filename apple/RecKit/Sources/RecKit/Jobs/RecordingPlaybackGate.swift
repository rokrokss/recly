import Foundation

/// One recorder's playback gate. Capture awaits this before opening an audio device.
@MainActor
public final class RecordingPlaybackGate {
    public private(set) var blocked = false
    private let players = NSHashTable<RecordingPlayer>.weakObjects()

    public init() {}

    func attach(_ player: RecordingPlayer) {
        players.add(player)
        player.setCaptureBlocked(blocked)
    }

    public func setBlocked(_ blocked: Bool) {
        self.blocked = blocked
        for player in players.allObjects { player.setCaptureBlocked(blocked) }
    }
}
