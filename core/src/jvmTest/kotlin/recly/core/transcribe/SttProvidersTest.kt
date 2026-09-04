package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import recly.core.workflow.WorkflowParser

/**
 * The editors offer [WorkflowParser.STT_PROVIDERS] and the runner resolves whatever the step names
 * through [SttProviders]. A name that is in one and not the other passes validation on the editor
 * and then fails on the device, so the two lists are pinned against each other here.
 */
class SttProvidersTest {
    @Test
    fun `every provider the parser offers is in the registry under its own name`() {
        assertEquals(
            WorkflowParser.STT_PROVIDERS,
            WorkflowParser.STT_PROVIDERS.map { SttProviders.create(it)?.name },
        )
    }

    @Test
    fun `a name the registry does not know is null rather than a broken provider`() {
        assertNull(SttProviders.create("whisper-local"))
    }

    @Test
    fun `the providers that answer on one long request are the ones the editors warn about`() {
        assertEquals(
            listOf("clova", "openai", "groq", "together", "mistral", "elevenlabs", "deepgram", "azure"),
            WorkflowParser.STT_PROVIDERS.filter { SttProviders.synchronous(it) },
        )
    }
}
