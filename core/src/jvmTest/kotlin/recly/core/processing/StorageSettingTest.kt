@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.processing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import okio.fakefilesystem.FakeFileSystem
import recly.core.model.Step
import recly.core.storage.StorageKind
import recly.core.testing.FakeClock
import recly.core.testing.FakeUbiquityContainer
import recly.core.testing.inMemoryDatabase
import recly.core.testing.testDeps

/**
 * docs/03 "Storage location": a fresh install uploads to Drive as before; the storage changes only through
 * [ProcessingSettingsRepository.setStorage] — iCloud only where the shell has a container — and a
 * recording keeps the storage it froze when it started.
 */
class StorageSettingTest {
    private val clock = FakeClock()
    private val db = inMemoryDatabase()
    private val withICloud = testDeps(
        clock = clock,
        ubiquity = FakeUbiquityContainer(FakeFileSystem(clock), clock),
    )

    @Test
    fun `a fresh install uploads to Drive`() = runBlocking<Unit> {
        val document = ProcessingSettingsRepository(db, withICloud).initialize().document

        assertEquals(StorageKind.DRIVE, document.settings.storage.provider)
        assertEquals(StorageKind.DRIVE, upload(document).store)
    }

    @Test
    fun `choosing iCloud is saved at once as the next revision, and new plans upload there`() = runBlocking<Unit> {
        val repository = ProcessingSettingsRepository(db, withICloud)
        val before = repository.initialize().document

        val saved = assertIs<ProcessingSaveResult.Saved>(repository.setStorage(StorageKind.ICLOUD)).document

        assertEquals(before.revision + 1, saved.revision)
        assertEquals(StorageKind.ICLOUD, repository.storage())
        assertEquals(StorageKind.ICLOUD, upload(saved).store)
        val again = assertIs<ProcessingSaveResult.Saved>(repository.setStorage(StorageKind.ICLOUD)).document
        assertEquals(saved.revision, again.revision, "choosing what is chosen changes nothing")
    }

    @Test
    fun `a device without an iCloud container cannot choose it`() = runBlocking<Unit> {
        val repository = ProcessingSettingsRepository(db, testDeps(clock = clock))
        repository.initialize()

        assertIs<ProcessingSaveResult.Invalid>(repository.setStorage(StorageKind.ICLOUD))
        assertEquals(StorageKind.DRIVE, repository.storage())
    }

    @Test
    fun `saving the form and importing settings keep the storage this device chose`() = runBlocking<Unit> {
        val repository = ProcessingSettingsRepository(db, withICloud)
        repository.initialize()
        val chosen = assertIs<ProcessingSaveResult.Saved>(repository.setStorage(StorageKind.ICLOUD)).document

        val form = ProcessingDraft.from(chosen.settings).settings().copy(storage = ProcessingStorage(provider = StorageKind.DRIVE))
        val saved = assertIs<ProcessingSaveResult.Saved>(repository.save(form, chosen.revision)).document
        assertEquals(StorageKind.ICLOUD, saved.settings.storage.provider)

        val exported = ProcessingSettingsParser.serialize(saved.copy(settings = saved.settings.copy(storage = ProcessingStorage())))
        val imported = assertIs<ProcessingSaveResult.Saved>(repository.importJson(exported, saved.revision)).document
        assertEquals(StorageKind.ICLOUD, imported.settings.storage.provider)
    }

    @Test
    fun `a recording keeps the storage it froze when it started`() = runBlocking<Unit> {
        val repository = ProcessingSettingsRepository(db, withICloud)
        repository.capture(RECORDING_ID)

        repository.setStorage(StorageKind.ICLOUD)

        assertEquals(StorageKind.DRIVE, upload(repository.recordingSnapshot(RECORDING_ID)!!).store)
    }

    private fun upload(document: ProcessingSettingsDocument): Step.DriveUpload =
        ProcessingPlan.compile(document).steps.filterIsInstance<Step.DriveUpload>().single()

    private companion object {
        const val RECORDING_ID = "01J9ABCDEF0123456789ABCDEF"
    }
}
