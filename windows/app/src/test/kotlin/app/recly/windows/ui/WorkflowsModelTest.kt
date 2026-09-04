@file:OptIn(ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.recly.windows.ui

import app.recly.windows.FakeSettings
import app.recly.windows.FixedClock
import app.recly.windows.MemorySecrets
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import app.recly.windows.i18n.text
import app.recly.windows.ui.theme.ProcessingState
import app.recly.windows.workflow.DEFAULT_STT_PROVIDER
import app.recly.windows.workflow.StepEdit
import app.recly.windows.workflow.StepKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import recly.core.model.Language
import recly.core.model.Speakers
import recly.core.model.Step
import recly.core.model.Workflow
import recly.core.model.WorkflowsDocument
import recly.core.sync.ImportResult
import recly.core.sync.SaveResult
import recly.core.workflow.ParseResult
import recly.core.workflow.WorkflowDocuments
import recly.core.workflow.WorkflowParser

/**
 * Deliverable 7: the editor's two rules — a document something else changed underneath it (Stale)
 * and two writes that must not lose each other (the mutator's mutex) — and docs/05's export and
 * import, which is the only way definitions move between devices now. The rules themselves are
 * `recly.core.workflow.WorkflowMutator`; what is under test here is that the window goes through it
 * and does the right thing with what it gets back.
 */
class WorkflowsModelTest {

    @Test
    fun `a save on a workflow something else replaced is refused, not merged`() = runTest {
        val documents = FakeDocuments(document())
        val model = model(documents)
        model.reload()
        model.edit(MEETING)
        model.update { it.copy(name = "내 회의") }

        // An import lands: same id, a newer version. There is no three-way merge.
        documents.doc = document(meetingUpdatedAt = "2026-08-27T11:00:00.000Z")
        model.save()

        assertEquals(true, model.editor?.stale)
        assertEquals(0, documents.saves, "nothing may be written over the other device's edit")
        // The editor keeps what the user typed: reopening (and losing it) is their choice to make.
        assertEquals("내 회의", model.editor?.edit?.name)
    }

    @Test
    fun `reopening a stale editor starts again from what is stored`() = runTest {
        val documents = FakeDocuments(document())
        val model = model(documents)
        model.reload()
        model.edit(MEETING)
        model.update { it.copy(name = "내 회의") }
        documents.doc = document(meetingName = "다른 기기의 이름", meetingUpdatedAt = "2026-08-27T11:00:00.000Z")
        model.save()

        model.reopen()

        assertEquals("다른 기기의 이름", model.editor?.edit?.name)
        assertEquals(false, model.editor?.stale)
    }

    /** ADR-016: the mark is a local pointer, so setting it writes nothing to the document. */
    @Test
    fun `marking a row as this device's default writes nothing to the document`() = runTest {
        val defaults = FakeDeviceDefault()
        val documents = FakeDocuments(document())
        val model = model(documents, defaults = defaults)
        model.reload()
        assertNull(model.items.single { it.id == MEETING }.isDeviceDefault.takeIf { it })

        model.setDefault(model.items.single { it.id == MEMO })

        assertEquals(MEMO, defaults.id)
        assertEquals(0, documents.saves, "the pointer never reaches workflows.json")
        assertEquals(listOf(false, true), model.items.map { it.isDeviceDefault }, "only one row is marked")
    }

    /**
     * ADR-016 superseded the isDefault-undeletable rule (2065dbb): any workflow may be deleted, and
     * the row says what deleting the marked one costs before it happens.
     */
    @Test
    fun `the row this device defaults to says what deleting it would cost, and still deletes`() = runTest {
        val defaults = FakeDeviceDefault(MEETING)
        val documents = FakeDocuments(document())
        val model = model(documents, defaults = defaults)
        model.reload()
        val meeting = model.items.single { it.id == MEETING }
        assertTrue(meeting.isDeviceDefault, "the row knows, which is what puts the warning on it")

        model.delete(meeting)

        assertEquals(1, documents.saves)
        assertEquals(listOf(MEMO), documents.doc.workflows.map { it.id })
        // The core clears a pointer whose workflow the same save deleted; the model reads it back.
        assertNull(model.items.singleOrNull { it.isDeviceDefault })
    }

    /**
     * The row's button asks rather than writes (the same confirmation Android asks), and the answer
     * it is waiting for is about a row whose default mark can move while the question is up — so
     * the confirmation is the row as it is now rather than the copy the click made.
     */
    @Test
    fun `the delete is asked before it is done, and the question follows the default pointer`() = runTest {
        val documents = FakeDocuments(document())
        val model = model(documents, defaults = FakeDeviceDefault(MEMO))
        model.reload()

        model.askToDelete(model.items.single { it.id == MEETING })

        assertEquals(MEETING, model.deleteConfirm?.id)
        assertEquals(false, model.deleteConfirm?.isDeviceDefault)
        assertEquals(0, documents.saves, "asking is not deleting")

        model.setDefault(model.items.single { it.id == MEETING })
        assertEquals(true, model.deleteConfirm?.isDeviceDefault, "the warning belongs on it now")

        model.cancelDelete()
        assertNull(model.deleteConfirm)
        assertEquals(0, documents.saves)

        model.askToDelete(model.items.single { it.id == MEETING })
        model.delete(model.items.single { it.id == MEETING })

        assertNull(model.deleteConfirm, "answering closes it")
        assertEquals(1, documents.saves)
        assertEquals(listOf(MEMO), documents.doc.workflows.map { it.id })
    }

    /**
     * A question about a row that is gone is over for good: an import can bring the same fixed id
     * back, and a stale confirmation re-arming against the new workflow would be a delete nobody
     * asked of it.
     */
    @Test
    fun `a confirmation whose row disappeared does not re-arm when the id comes back`() = runTest {
        val documents = FakeDocuments(document())
        val model = model(documents)
        model.reload()
        model.askToDelete(model.items.single { it.id == MEETING })
        assertEquals(MEETING, model.deleteConfirm?.id)

        // An import replaces the document without the meeting workflow.
        documents.doc = document().copy(workflows = document().workflows.filterNot { it.id == MEETING })
        model.reload()
        assertNull(model.deleteConfirm, "the row is gone, so the question is too")

        // The same fixed id returns with the next import: nothing may reopen the old question.
        documents.doc = document()
        model.reload()
        assertNull(model.deleteConfirm, "a returned id is a new workflow, not an old answer")
        assertEquals(0, documents.saves)
    }

    @Test
    fun `a workflow that is not the default is deleted`() = runTest {
        val documents = FakeDocuments(document())
        val model = model(documents)
        model.reload()

        model.delete(model.items.single { it.id == MEMO })

        assertEquals(1, documents.saves)
        assertEquals(listOf(MEETING), documents.doc.workflows.map { it.id })
    }

    @Test
    fun `two writes at once do not lose each other`() = runTest {
        // Both mutations rewrite the whole document. Applied to the same snapshot, one of the two
        // deletes would disappear; the mutator makes the second one read the first one's result.
        val documents = FakeDocuments(document())
        val model = model(documents)
        model.reload()
        val gate = CompletableDeferred<Unit>()
        documents.beforeSave = { if (documents.saves == 0) gate.await() }

        val first = launch { model.delete(model.items.first { it.id == MEETING }) }
        runCurrent()
        val second = launch { model.delete(model.items.first { it.id == MEMO }) }
        runCurrent()
        gate.complete(Unit)
        listOf(first, second).forEach { it.join() }
        advanceUntilIdle()

        assertEquals(2, documents.saves)
        assertEquals(emptyList(), documents.doc.workflows.map { it.id })
    }

    /**
     * Sol UX-L3 #2 · docs/09 트렌드 2: the button that asked says "…" for as long as the write runs.
     * Set after the write instead, an operation slower than the button's own window re-enables Save
     * mid-flight, and the second press is a duplicate of the first.
     */
    @Test
    fun `a save that has not come back yet is still processing`() = runTest {
        val documents = FakeDocuments(document())
        val model = model(documents)
        model.reload()
        model.edit(MEETING)
        val gate = CompletableDeferred<Unit>()
        documents.beforeSave = { gate.await() }

        val saving = launch { model.save() }
        runCurrent()
        assertEquals(ProcessingState.PROCESSING, model.action, "the write is still in flight")

        gate.complete(Unit)
        saving.join()

        assertEquals(ProcessingState.DONE, model.action)
    }

    /** Sol UX-L3 r2: a write that throws must not leave the button "…" for ever. */
    @Test
    fun `a save whose write throws leaves the button failed`() = runTest {
        val documents = FakeDocuments(document())
        val model = model(documents)
        model.reload()
        model.edit(MEETING)
        documents.beforeSave = { throw IllegalStateException("disk") }

        val thrown = runCatching { model.save() }.exceptionOrNull()

        assertEquals("disk", thrown?.message, "the throwable carries on")
        assertEquals(ProcessingState.FAILED, model.action)
    }

    /** The same for the export the settings window starts (`ShellModel.exportWorkflows`). */
    @Test
    fun `an export that has not come back yet is still processing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val model = model(FakeDocuments(document()), saveFile = { _, _ -> gate.await(); true })

        val exporting = launch { model.exportWorkflows() }
        runCurrent()
        assertEquals(ProcessingState.PROCESSING, model.action)

        gate.complete(Unit)
        exporting.join()

        assertEquals(ProcessingState.DONE, model.action)
    }

    @Test
    fun `a save that comes back to a different editor leaves it alone`() = runTest {
        // Sol M2-L4 #1: closing the editor on screen would throw away edits nobody asked to discard.
        val documents = FakeDocuments(document())
        val model = model(documents)
        model.reload()
        model.edit(MEETING)
        val gate = CompletableDeferred<Unit>()
        documents.beforeSave = { gate.await() }

        val saving = launch { model.save() }
        runCurrent()
        model.edit(MEMO)
        gate.complete(Unit)
        saving.join()

        assertEquals(MEMO, model.editor?.edit?.id, "the editor the user is looking at survives")
        assertEquals(SAVED_ELSEWHERE_NOTICE.message(), model.message)
    }

    @Test
    fun `the parser's verdict is shown on the editor that asked for it`() = runTest {
        val documents = FakeDocuments(document(), invalid = listOf("workflow $MEETING: name must be 1..40"))
        val model = model(documents)
        model.reload()
        model.edit(MEETING)

        model.save()

        assertEquals(listOf("workflow $MEETING: name must be 1..40"), model.editor?.errors)
        assertNotNull(model.editor)
    }

    @Test
    fun `a generated webhook secret is shown once and copied`() = runTest {
        // docs/04: the `whsec_` value is never readable again after it is stored.
        val copied = mutableListOf<String>()
        val model = model(FakeDocuments(document()), copy = { copied += it })
        model.reload()
        model.openSecrets()
        model.secretName("hook_key")

        model.generateSecret()
        val shown = model.secretForm?.value
        model.saveSecret()

        assertNotNull(shown)
        assertTrue(shown.startsWith("whsec_"), shown)
        assertEquals(listOf(shown), copied)
        assertEquals(listOf("hook_key"), model.secretNames)
        assertNull(model.secretForm, "the form closes and the value is gone with it")
    }

    @Test
    fun `a secret name the parser would reject never reaches the store`() = runTest {
        val model = model(FakeDocuments(document()))
        model.reload()
        model.openSecrets()
        model.secretName("Hook Key")
        model.secretValue("x")

        model.saveSecret()

        assertNotNull(model.secretForm?.error)
        assertTrue(model.secretNames.isEmpty())
    }

    /**
     * docs/05 "시크릿": a key entered from a step goes to the store, and the step is told the name —
     * which is the only half of it the document is allowed to carry.
     */
    @Test
    fun `a secret entered from a step reports the name it stored`() = runTest {
        val model = model(FakeDocuments(document(transcribing = true)))
        model.reload()
        model.openSecrets(stepId = "stt")
        model.secretName("clova_key")
        model.secretValue("the key itself")

        assertEquals("stt", model.secretForm?.stepId, "the form is shown on the step that asked")
        assertEquals("clova_key", model.saveSecret(), "the name is what the step's secretRef gets")
        assertEquals(listOf("clova_key"), model.secretNames)
    }

    @Test
    fun `a workflow whose secret this device does not have is flagged`() = runTest {
        // docs/05 "새 기기": the definition synced, the key did not.
        val model = model(FakeDocuments(document(secretRef = "hook_key")))
        model.reload()

        assertEquals(listOf("hook_key"), model.items.first { it.id == MEMO }.missingSecrets)
    }

    /**
     * M7-L3 deliverable 5. The editor holds every number and every optional string as text, so the
     * risk is not that a save fails — it is that opening a `transcribe` step and saving it back
     * quietly changes it. Nothing about it may move.
     */
    @Test
    fun `a transcribe workflow survives the editor's round trip`() = runTest {
        val documents = FakeDocuments(document(transcribing = true))
        val stored = documents.doc.workflows.first { it.id == MEETING }
        val model = model(documents)
        model.reload()

        model.edit(MEETING)
        model.save()

        val saved = documents.doc.workflows.first { it.id == MEETING }
        assertEquals(stored.steps, saved.steps, "every step came back as it went in")
        assertTrue(
            WorkflowParser.parse(WorkflowParser.serialize(documents.doc)) is ParseResult.Ok,
            "and the document the editor wrote is one the parser accepts",
        )
    }

    /**
     * The same round trip over values nobody touched. Whitespace is not an empty field: the parser
     * only refuses an empty `model`, so trimming one on the way out would rewrite a step the user
     * only looked at.
     */
    @Test
    fun `whitespace in a model nobody edited survives the round trip`() = runTest {
        val documents = FakeDocuments(document(transcribing = true, transcribeModel = " x "))
        val stored = documents.doc.workflows.first { it.id == MEETING }
        val model = model(documents)
        model.reload()

        model.edit(MEETING)
        model.save()

        val saved = documents.doc.workflows.first { it.id == MEETING }
        assertEquals(stored.steps, saved.steps, "every step came back as it went in")
    }

    /** docs/08's order constraint, which the editor reports before it offers to save. */
    @Test
    fun `moving a transcribe step in front of its upload is reported on that step`() = runTest {
        val model = model(FakeDocuments(document(transcribing = true)))
        model.reload()
        model.edit(MEETING)

        // 2nd step (transcribe) to the front of the 1st (drive.upload).
        model.moveStep(1, 0)

        assertEquals(
            WorkflowParser.TRANSCRIBE_NEEDS_UPLOAD,
            model.editor?.order?.get("stt"),
            "the complaint is hung on the step that has to move: ${model.editor?.order}",
        )
        assertNull(model.editor?.order?.get("upload"), "the upload step has no order of its own to keep")
    }

    /** docs/08: a new transcribe step arrives on the provider table's first row. */
    @Test
    fun `an added transcribe step is prefilled`() = runTest {
        val model = model(FakeDocuments(document()))
        model.reload()
        model.edit(MEETING)

        model.addStep(StepKind.TRANSCRIBE)

        val step = model.editor?.edit?.steps?.last() as StepEdit.Transcribe
        assertEquals("stt", step.id)
        assertEquals(DEFAULT_STT_PROVIDER, step.provider)
    }

    /** One Drive upload per workflow: a second `addStep(DRIVE)` changes nothing, a webhook still lands. */
    @Test
    fun `a second drive upload is not added`() = runTest {
        val model = model(FakeDocuments(document()))
        model.reload()
        model.edit(MEETING)
        val before = model.editor?.edit?.steps
        assertEquals(1, before?.count { it is StepEdit.Drive })

        model.addStep(StepKind.DRIVE)
        assertEquals(before, model.editor?.edit?.steps)

        model.addStep(StepKind.HOOK)
        assertEquals(before!!.size + 1, model.editor?.edit?.steps?.size)
    }

    /** docs/05 "새 기기": a key is a key, whichever kind of step named it. */
    @Test
    fun `a transcribe key this PC does not have is flagged too`() = runTest {
        val model = model(FakeDocuments(document(transcribing = true)))
        model.reload()

        assertEquals(listOf("clova_key"), model.items.first { it.id == MEETING }.missingSecrets)
    }

    /**
     * docs/05 "워크플로우 내보내기": the document exactly as it is stored, under the name every shell
     * suggests — and nothing is claimed until the file has actually been written.
     */
    @Test
    fun `an export writes the stored document under the shared file name`() = runTest {
        val written = mutableListOf<Pair<String, String>>()
        val model = model(
            FakeDocuments(document()),
            exportJson = { "{\"schema\":3}" },
            saveFile = { name, contents -> written += name to contents; true },
        )

        model.exportWorkflows()

        assertEquals(listOf(WORKFLOWS_FILE_NAME to "{\"schema\":3}"), written)
        assertEquals(ProcessingState.DONE, model.action)
        assertEquals(Str.WORKFLOWS_EXPORTED.message(), model.message)
    }

    /** A save dialog the user closed asked for nothing, so the button goes back to where it was. */
    @Test
    fun `an export the user cancelled says nothing`() = runTest {
        val model = model(FakeDocuments(document()), saveFile = { _, _ -> false })

        model.exportWorkflows()

        assertEquals(ProcessingState.IDLE, model.action)
        assertNull(model.message)
    }

    /**
     * docs/05 "워크플로우 가져오기": an import replaces the whole document, so the count is put to the
     * user *before* anything is written — the picked file is only parsed until they agree.
     */
    @Test
    fun `a picked file is confirmed before it replaces anything`() = runTest {
        val imports = mutableListOf<String>()
        val file = WorkflowParser.serialize(document(meetingName = "다른 기기의 회의"))
        val model = model(
            FakeDocuments(document()),
            openFile = { file },
            importJson = { json -> imports += json; ImportResult.Imported(workflows = 2) },
        )
        model.reload()

        model.pickImport()

        assertEquals(PickedWorkflows(file, 2), model.importConfirm)
        assertEquals(emptyList(), imports, "nothing is written until the user agrees")

        model.confirmImport()

        assertEquals(listOf(file), imports)
        assertNull(model.importConfirm)
        assertEquals(Str.WORKFLOWS_IMPORTED.message(2), model.message)
    }

    @Test
    fun `a cancelled confirmation writes nothing`() = runTest {
        val imports = mutableListOf<String>()
        val model = model(
            FakeDocuments(document()),
            openFile = { WorkflowParser.serialize(document()) },
            importJson = { json -> imports += json; ImportResult.Imported(workflows = 2) },
        )
        model.pickImport()

        model.cancelImport()
        model.confirmImport()

        assertNull(model.importConfirm)
        assertEquals(emptyList(), imports)
    }

    /**
     * A file that does not parse never reaches a confirmation: there is nothing to confirm and
     * `importJson` refuses it without writing, so what the user sees is the parser's own list —
     * docs/02 owns those words and they are not the shell's to translate.
     */
    @Test
    fun `an unreadable file is refused with the parser's own errors`() = runTest {
        val model = model(
            FakeDocuments(document()),
            openFile = { "not json at all" },
            importJson = { ImportResult.Invalid(listOf("schema is missing")) },
        )

        model.pickImport()

        assertNull(model.importConfirm)
        assertEquals(UiMessage.Text("schema is missing"), model.message)
        assertEquals(ProcessingState.FAILED, model.action)
    }

    /** An open dialog the user closed is not a failure either. */
    @Test
    fun `an import the user cancelled says nothing`() = runTest {
        val model = model(FakeDocuments(document()), openFile = { null })

        model.pickImport()

        assertEquals(ProcessingState.IDLE, model.action)
        assertNull(model.message)
    }

    /** The file the dialog could not read is the shell's own complaint, and it names the reason. */
    @Test
    fun `a file that would not open says so with the reason`() = runTest {
        val model = model(FakeDocuments(document()))

        model.fileFailed("access is denied")

        assertEquals(Str.WORKFLOWS_FILE_FAILED.message("access is denied"), model.message)
        assertEquals(ProcessingState.FAILED, model.action)
    }

    /**
     * Sol I18N-L3 #2: an import is started from the settings window, and the editor's banner — the
     * only thing that would otherwise say what became of it — may not be on screen at all. So the
     * tray takes the line, which is where a user who pressed the button will look next.
     */
    @Test
    fun `an import the settings window started reaches the tray`() = runTest {
        val model = model(
            FakeDocuments(document()),
            openFile = { "not json at all" },
            importJson = { ImportResult.Invalid(listOf("schema is missing")) },
        )
        val shell = ShellModel(localization = Localization(FakeSettings()) { StringTable.BASE })
        val english = StringTable.of(StringTable.BASE)

        model.pickImport()
        shell.adopt(model)

        assertEquals("schema is missing", shell.status.text(english))
        assertEquals(
            "schema is missing",
            trayMenu(shell, english, quit = {}).filterIsInstance<TrayEntry.Item>().first().label,
        )
    }

    private fun model(
        documents: FakeDocuments,
        copy: (String) -> Unit = {},
        clock: FixedClock = FixedClock(),
        defaults: FakeDeviceDefault = FakeDeviceDefault(),
        exportJson: suspend () -> String = { WorkflowParser.serialize(documents.doc) },
        importJson: suspend (String) -> ImportResult = { ImportResult.Imported(workflows = 0) },
        saveFile: suspend (String, String) -> Boolean = { _, _ -> true },
        openFile: suspend () -> String? = { null },
    ) = WorkflowsModel(
        documents = documents,
        secrets = MemorySecrets(),
        clock = clock,
        exportJson = exportJson,
        importJson = importJson,
        saveFile = saveFile,
        openFile = openFile,
        deviceDefault = { defaults.id?.takeIf { id -> documents.doc.workflows.any { it.id == id } } },
        setDeviceDefault = { defaults.id = it },
        clipboard = copy,
    )

    private fun document(
        meetingName: String = "회의",
        meetingUpdatedAt: String = "2026-08-27T09:00:00.000Z",
        secretRef: String? = null,
        /** docs/08: the meeting workflow uploads and transcribes. */
        transcribing: Boolean = false,
        transcribeModel: String? = null,
    ) = WorkflowsDocument(
        schema = 2,
        revision = 1,
        updatedAt = "2026-08-27T09:00:00.000Z",
        updatedBy = "device",
        workflows = listOf(
            Workflow(
                id = MEETING,
                name = meetingName,
                updatedAt = meetingUpdatedAt,
                steps = if (transcribing) {
                    listOf(
                        Step.DriveUpload(id = "upload"),
                        Step.Transcribe(
                            id = "stt",
                            provider = "clova",
                            secretRef = "clova_key",
                            invokeUrl = "https://clovaspeech-gw.example.com/external/v1/1234/abcd",
                            language = Language.KO_EN,
                            speakers = Speakers(min = 2, max = 6),
                            model = transcribeModel,
                        ),
                    )
                } else {
                    listOf(Step.DriveUpload(id = "upload"))
                },
            ),
            Workflow(
                id = MEMO,
                name = "메모",
                updatedAt = "2026-08-27T09:00:00.000Z",
                steps = listOf(Step.Webhook(id = "hook", url = "https://example.com", secretRef = secretRef)),
            ),
        ),
    )

    private companion object {
        const val MEETING = "00000000000000000000RECMTG"
        const val MEMO = "00000000000000000000RECMEM"
    }
}

/** The shell's switches, kept off the developer's own `java.util.prefs` (as in `TrayMenuTest`). */
/**
 * ADR-016's local pointer, in memory. The `deviceDefault` lambda resolves it against the document
 * the way the core does — a pointer at a workflow another device deleted selects nothing.
 */
private class FakeDeviceDefault(var id: String? = null)

/** The document as the model sees it, with the two hooks a race needs to be made deterministic. */
private class FakeDocuments(
    var doc: WorkflowsDocument,
    private val invalid: List<String> = emptyList(),
) : WorkflowDocuments {

    var saves = 0
        private set

    /** Runs inside `save`, before it takes effect. */
    var beforeSave: suspend () -> Unit = {}

    override suspend fun current(): WorkflowsDocument = doc

    override suspend fun save(document: WorkflowsDocument): SaveResult {
        beforeSave()
        if (invalid.isNotEmpty()) return SaveResult.Invalid(invalid)
        saves++
        doc = document
        return SaveResult.Saved(document)
    }
}
