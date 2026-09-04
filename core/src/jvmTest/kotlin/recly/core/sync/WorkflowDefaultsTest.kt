package recly.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/07 §6: the seeded workflow is named in the app's language at the moment a device first
 * asks, and its id does not move with the language — a shell asks for it by id
 * ([WorkflowRepository.seed]) whatever the app is speaking.
 */
class WorkflowDefaultsTest {

    private val repository = WorkflowHarness().workflows

    @Test
    fun `a Korean app seeds Korean names`() {
        assertEquals(listOf("메모"), repository.defaults("ko").workflows.map { it.name })
    }

    @Test
    fun `a region does not change the language`() {
        assertEquals(listOf("메모"), repository.defaults("ko-KR").workflows.map { it.name })
    }

    @Test
    fun `every other language gets the English base`() {
        listOf("en", "en-GB", "ja", "kok", "").forEach { locale ->
            assertEquals(
                listOf("Memo"),
                repository.defaults(locale).workflows.map { it.name },
                "seed names for '$locale'",
            )
        }
    }

    @Test
    fun `the seed id is the same whatever the language`() {
        assertEquals(
            listOf(WorkflowRepository.MEMO_ID),
            repository.defaults("en").workflows.map { it.id },
        )
        assertEquals(
            repository.defaults("en").workflows.map { it.id },
            repository.defaults("ko").workflows.map { it.id },
        )
    }
}
