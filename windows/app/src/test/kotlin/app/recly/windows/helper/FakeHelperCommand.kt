package app.recly.windows.helper

import java.io.File

/**
 * The helper the protocol tests spawn, and the knobs they ask it for.
 *
 * By default that is `windows/app/dev/FakeHelper.java`, run by the JDK's single-file source
 * launcher — no build step, no classpath, and the same command on every platform the tests run on.
 *
 * With `-Drecly.captureHelper="<command line>"` it is the **real** Rust helper from
 * `windows/capture-helper`, driven by its `--fake-source sine` capture (docs/lanes/M6-L2 done
 * condition). The property is a command line rather than a path, exactly like the app's own
 * `RECLY_CAPTURE_HELPER`, so flags such as `--encoder ffmpeg` need no knob of their own:
 *
 * ```
 * ./gradlew :windows:app:test --tests '*HelperClientTest*' \
 *   -Drecly.captureHelper="$PWD/windows/capture-helper/target/debug/recly-capture-helper"
 * ```
 *
 * The knobs below are `FakeHelper.java`'s; [translate] says them in the real helper's flags. A knob
 * with no real equivalent throws rather than quietly running a different test — the real helper
 * writes real audio to the `start` command's directory, so only `HelperClientTest` (which asserts
 * the protocol) is meaningful against it, not the tests that assert what is left on disk.
 */
object FakeHelperCommand {

    const val HELPER_PROPERTY = "recly.captureHelper"

    fun command(vararg args: String): List<String> {
        val real = System.getProperty(HELPER_PROPERTY)?.takeIf { it.isNotBlank() }
        return if (real != null) {
            real.trim().split(" ") + translate(args)
        } else {
            listOf(java(), source()) + args
        }
    }

    /** `FakeHelper.java`'s arguments as `recly-capture-helper`'s. */
    private fun translate(args: Array<out String>): List<String> = buildList {
        add("--fake-source")
        add("sine")
        // `FakeHelper` defaults to one-second parts; the real helper would otherwise take the
        // `start` command's 900 at its word.
        var segmentSec = "1.0"
        for (arg in args) {
            when {
                arg.startsWith("sec=") -> segmentSec = arg.removePrefix("sec=")
                arg.startsWith("parts=") -> { add("--parts"); add(arg.removePrefix("parts=")) }
                arg.startsWith("micInUse=") -> { add("--mic-in-use"); add(arg.removePrefix("micInUse=")) }
                arg == "die" -> { add("--parts"); add("1"); add("--die") }
                arg == "noise" -> add("--noise")
                // The real helper always writes the audio it reports; the fake only on request.
                arg == "write" -> Unit
                arg == "hang" -> add("--hang")
                else -> error("$arg has no equivalent in the real capture helper")
            }
        }
        add("--segment-sec")
        add(segmentSec)
    }

    private fun java(): String = ProcessHandle.current().info().command()
        .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java")

    /** The test's working directory is `windows/app` (Gradle's default for a `Test` task). */
    private fun source(): String {
        val file = File("dev/FakeHelper.java")
        check(file.exists()) { "fake helper not found at ${file.absolutePath}" }
        return file.absolutePath
    }
}
