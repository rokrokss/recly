package recly.core.recording

/**
 * docs/03 "Free storage on the device": how much free space a recording needs on the volume that holds the
 * recording directory. One rule for all six shells — the phone and Galaxy Watch (`StatFs.availableBytes`),
 * the iPhone and the Mac (`volumeAvailableCapacityForImportantUsage`), the Apple Watch
 * (`volumeAvailableCapacity`, the only figure watchOS has) and Windows (`FileStore.usableSpace`) each read
 * their own figure and hand it over in bytes.
 *
 * - A start needs [START_BYTES] (200 MB): under it nothing is opened and the user is told why. At 32 kbps
 *   mono that is still about 14 hours of audio, so the margin is the system's, not the recording's.
 * - A running recording is checked at every part boundary and after a write that failed; under
 *   [RECORDING_BYTES] (50 MB) it is stopped the normal way — the parts already written are filed, then
 *   transferred and uploaded as usual — and the user is told why. A desktop records three tracks, and the
 *   floors are the same.
 * - A figure the platform cannot read (negative) refuses and stops nothing, as an unreadable battery does.
 *
 * Decimal megabytes, as the platforms' own storage screens count them.
 */
object StorageFloor {
    const val START_BYTES: Long = 200_000_000L
    const val RECORDING_BYTES: Long = 50_000_000L

    /**
     * True when [freeBytes] is too little: to start a recording when [recording] is false, to keep one
     * going when it is true.
     */
    fun tooLow(freeBytes: Long, recording: Boolean): Boolean =
        freeBytes >= 0 && freeBytes < if (recording) RECORDING_BYTES else START_BYTES
}
