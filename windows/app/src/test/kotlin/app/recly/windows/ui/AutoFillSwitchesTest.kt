package app.recly.windows.ui

import app.recly.windows.core.AppModule
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import recly.core.chatgpt.SummaryPreferences

/**
 * docs/08 "Titles and speaker names": Settings → ChatGPT's two switches reach the core's own preferences, each its
 * own field, with no sign-in — the core reads them when a transcript is published.
 */
class AutoFillSwitchesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir: File = Files.createTempDirectory("recly-autofill-").toFile()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `each switch saves its own flag, signed out`() = runBlocking {
        val graph = AppModule.build(dataDir = File(dir, "data").absolutePath.toPath())
        val summaries = graph.core.summaries
        val chatGpt = ChatGptViewModel(graph.core.chatGpt, summaries, graph.loopback, scope, graph.core.deps.logger)

        suspend fun until(ready: (SummaryPreferences) -> Boolean) = withTimeout(TIMEOUT_MS) {
            while (!ready(summaries.preferences())) delay(POLL_MS)
        }

        chatGpt.toggleAutoTitle(true)
        until { it.autoTitle && !it.autoSpeakers }
        chatGpt.toggleAutoSpeakers(true)
        until { it.autoTitle && it.autoSpeakers }
        chatGpt.toggleAutoTitle(false)
        until { !it.autoTitle && it.autoSpeakers }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 20L
    }
}
