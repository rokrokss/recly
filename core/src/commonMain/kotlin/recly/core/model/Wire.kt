package recly.core.model

/**
 * Spec strings for the enums that appear in file names and DB columns. Every one of them
 * serialises as its lowercase Kotlin name (`Source.WATCH` → `watch`).
 */
internal val Source.wire: String get() = name.lowercase()

internal val Track.wire: String get() = name.lowercase()

internal val Platform.wire: String get() = name.lowercase()

internal val RecordingStatus.wire: String get() = name.lowercase()

/** The one that is not its own lowercase name: `KO_EN` is `ko-en` on the wire. */
internal val Language.wire: String get() = name.lowercase().replace('_', '-')
