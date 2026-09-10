package recly.core.recording

import okio.Path
import okio.Path.Companion.toPath
import recly.core.model.Platform

/** Recording locations survive an iOS/watchOS update that changes the app container UUID. */
internal class RecordingDirectory(private val dataDir: Path, private val platform: Platform) {
    private val recordings = dataDir / "recordings"

    /** Only the app's own recording folders are stored relative to its current data directory. */
    fun stored(dir: Path): String =
        if (dir.parent == recordings) "recordings/${dir.name}" else dir.toString()

    fun resolve(stored: String): Path {
        val path = stored.toPath()
        if (path.parent == "recordings".toPath() && path.name != "..") return dataDir / path
        if (path.parent == recordings) return path
        if (platform != Platform.IOS && platform != Platform.WATCHOS) return path

        // Previous builds stored absolute paths. Match the app-container layout, not just a
        // folder's basename: external directories and another app's data must remain untouched.
        val tail = path.segments.takeLast(9)
        if (path.isAbsolute && tail.size == 9 &&
            tail.take(3) == listOf("Containers", "Data", "Application") &&
            tail.subList(4, 8) == listOf("Library", "Application Support", dataDir.name, "recordings") &&
            tail.last() != ".."
        ) return recordings / path.name
        return path
    }
}
