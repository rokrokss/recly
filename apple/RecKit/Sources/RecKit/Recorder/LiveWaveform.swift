import Foundation

/// What the strip on the menu bar and the recording screen draws: the loudest sample of each tenth
/// of a second of the track a person hears, newest last, for the last thirty seconds.
///
/// It is here rather than in the shells because the only place the recorded audio exists is the
/// recorder's own write path — a second tap on the microphone to draw a picture with would be a
/// second answer to "is this being captured", and the whole point of the strip is that it is the
/// first one. A peak per window and nothing else: the levels are read off the buffer under the
/// recorder's lock, on the audio thread, so this allocates nothing and touches no floating-point
/// maths beyond a comparison.
struct LiveWaveform {
    /// 0.1 s at the 16 kHz every file is written at.
    static let windowFrames = 1_600
    /// Thirty seconds of them — more windows than any strip is wide, so the newest end of the ring
    /// is always the one on screen and the oldest simply falls off.
    static let capacity = 300

    let windowFrames: Int
    let capacity: Int

    /// The finished windows, as a ring: [next] is where the one after this goes, and [filled] how
    /// many of the slots have ever been written.
    private var ring: [Float]
    private var next = 0
    private var filled = 0
    /// The window still being counted. It is not in [peaks]: a bar that is drawn while it is still
    /// growing rises at the right edge and then stops, which reads as the level having dropped.
    private var openPeak: Float = 0
    private var openFrames = 0

    init(windowFrames: Int = LiveWaveform.windowFrames, capacity: Int = LiveWaveform.capacity) {
        precondition(windowFrames > 0 && capacity > 0, "a window of no frames never finishes")
        self.windowFrames = windowFrames
        self.capacity = capacity
        ring = Array(repeating: 0, count: capacity)
    }

    /// Counts [samples] into the windows, carrying the open one across the buffer boundary: a tap
    /// delivers 4096 frames at a time and a window is 1600, so a window ends inside a buffer far
    /// more often than between two.
    mutating func add(_ samples: UnsafeBufferPointer<Float>) {
        for sample in samples {
            // Clamped: a mix can exceed full scale, and a bar taller than the row is not louder.
            let level = min(abs(sample), 1)
            if level > openPeak { openPeak = level }
            openFrames += 1
            if openFrames == windowFrames {
                ring[next] = openPeak
                next = (next + 1) % capacity
                filled = min(filled + 1, capacity)
                openPeak = 0
                openFrames = 0
            }
        }
    }

    /// The finished windows, oldest first.
    var peaks: [Float] {
        guard filled > 0 else { return [] }
        let start = (next - filled + capacity) % capacity
        return (0 ..< filled).map { ring[(start + $0) % capacity] }
    }

    mutating func reset() {
        next = 0
        filled = 0
        openPeak = 0
        openFrames = 0
    }
}
