@file:OptIn(ExperimentalTime::class)

package recly.core.drive

import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.Path
import recly.core.db.RecDatabase
import recly.core.job.StepContext
import recly.core.job.StepFailure
import recly.core.job.StepOutcome
import recly.core.job.StepOutput
import recly.core.job.StepRunner
import recly.core.job.type
import recly.core.message.CoreMessage
import recly.core.model.Part
import recly.core.model.Step
import recly.core.model.wire
import recly.core.platform.CoreDeps
import recly.core.platform.Logger
import recly.core.recording.MetaWriter
import recly.core.recording.PartHasher
import recly.core.workflow.Template
import recly.core.workflow.TemplateContext

/**
 * `drive.upload` (docs/02): every selected part and then `meta.json` into `{folder}/{base}/`
 * (ADR-014). Meta goes last on purpose — downstream automations use "the meta is there" as the
 * signal that the recording is complete.
 *
 * Idempotent at every level: the folder is verified before it is trusted, a file whose Drive
 * `md5Checksum` already matches the local one is skipped, and the resume point of the file in
 * flight is written to `state_json` after every chunk. Nothing the user may have put in the
 * folder themselves is ever deleted — only a file this step just uploaded and found corrupt.
 */
class DriveUploadRunner(
    private val api: DriveApi,
    private val folders: FolderResolver,
    private val store: DriveStore,
    private val deps: CoreDeps,
) : StepRunner {
    override val type: String = TYPE

    /** docs/03 "다른 기기의 녹음": what the other devices are told is still to come after this upload. */
    private val marker = DriveFolderMarker(api, deps)

    override suspend fun run(ctx: StepContext): StepOutcome {
        val step = ctx.step as? Step.DriveUpload
            ?: throw StepFailure(
                retryable = false,
                reason = CoreMessage.STEP_FAILED.code("$TYPE runner got a ${ctx.step::class.simpleName}"),
            )
        val rendered = renderFolder(step, ctx)
        return StepOutcome.Done(uploaded(ctx, step, rendered))
    }

    private suspend fun uploaded(
        ctx: StepContext,
        step: Step.DriveUpload,
        rendered: String,
    ): StepOutput =
        try {
            attempt(ctx, step, rendered, DriveUploadState.from(ctx.state))
        } catch (e: DriveNotFound) {
            // A folder we had an id for is gone. Everything we remember about it — the cached path
            // ids and the per-file ids that lived inside it — is worthless, so drop it and resolve
            // the whole path once more. Once: a second 404 is not a stale cache.
            deps.logger.log(
                Logger.Level.WARN,
                "drive.reresolve",
                mapOf("stepId" to step.id, "path" to rendered, "reason" to e.message),
            )
            folders.invalidate(rendered)
            val cleared = DriveUploadState()
            ctx.saveState(cleared.toJson())
            try {
                attempt(ctx, step, rendered, cleared)
            } catch (again: DriveNotFound) {
                throw StepFailure(
                    retryable = true,
                    reason = CoreMessage.STEP_FAILED.code(again.message ?: "drive: not found"),
                )
            }
        }

    private suspend fun attempt(
        ctx: StepContext,
        step: Step.DriveUpload,
        rendered: String,
        initial: DriveUploadState,
    ): StepOutput {
        val meta = ctx.recording.meta
        val base = MetaWriter.baseName(meta)
        var state = initial

        // A saved folder id is a claim about Drive, not a fact. If the folder was deleted or
        // trashed between runs, everything we uploaded into it went with it.
        val saved = state.folderId
        if (saved != null && !folderAlive(saved)) {
            state = DriveUploadState()
            ctx.saveState(state.toJson())
        }
        val folder = folder(ctx, state, rendered, base)
        state = state.copy(folderId = folder.id, folderWebViewLink = folder.webViewLink)
        ctx.saveState(state.toJson())
        store.rememberRecordingFolder(ctx.recording.id, folder.id)
        // The folder exists before the first byte does, and it is the only thing another device can
        // see while this one uploads (docs/03 "다른 기기의 녹음"): what comes after this step goes on it
        // now, so a list elsewhere can say "전사 중" instead of showing a finished recording.
        marker.mark(folder.id, ctx.workflow.steps.dropWhile { it.id != ctx.step.id }.drop(1).map { it.type })
        // Before the first byte goes out, and into the output rather than the state: a NEEDS_SPACE
        // park drops `state_json` (docs/10), and "Drive에서도 삭제" (docs/03) still has to know which
        // folder this recording made. Overwritten by the full output when the step finishes.
        ctx.saveOutput(buildJsonObject { put("folderId", folder.id) })

        val uploaded = mutableListOf<JsonObject>()
        val parts = meta.parts
            .sortedWith(compareBy({ it.part }, { it.track.ordinal }))
        for (part in parts) {
            val (file, bytes) = upload(
                ctx = ctx,
                state = state,
                key = partKey(part),
                folderId = folder.id,
                name = part.file,
                path = ctx.recording.dir / part.file,
                mimeType = PART_MIME,
                md5 = partMd5(ctx, part),
                save = { state = it },
            )
            uploaded += entry(part.part, part.track.wire, part.file, bytes, part.sha256, file)
        }

        if (step.includeMeta) {
            val name = MetaWriter.metaFileName(base)
            val path = ctx.recording.dir / name
            val (file, bytes) = upload(
                ctx = ctx,
                state = state,
                key = META_KEY,
                folderId = folder.id,
                name = name,
                path = path,
                mimeType = META_MIME,
                md5 = PartHasher.md5(deps.fileSystem, path),
                save = { state = it },
            )
            uploaded += entry(0, META_KEY, name, bytes, PartHasher.sha256(deps.fileSystem, path), file)
        }

        return StepOutput(
            buildJsonObject {
                put("folderId", folder.id)
                folder.webViewLink?.let { put("folderWebViewLink", it) }
                put("path", "$rendered/$base")
                putJsonArray("files") { uploaded.forEach { add(it) } }
            },
        )
    }

    private fun renderFolder(step: Step.DriveUpload, ctx: StepContext): String =
        try {
            Template.render(step.folder, TemplateContext.of(ctx.recording.meta, ctx.workflow.name))
        } catch (e: IllegalArgumentException) {
            throw StepFailure(retryable = false, reason = CoreMessage.FOLDER_TEMPLATE.code(e.message))
        }

    /** A trashed folder is as good as deleted: Drive will not list its children any more. */
    private suspend fun folderAlive(folderId: String): Boolean {
        val json = api.getFile(folderId, "id,trashed,parents") ?: return false
        return json["trashed"]?.jsonPrimitive?.booleanOrNull != true
    }

    /**
     * The recording's own folder under the resolved path. A verified id skips both round trips;
     * the `description`/`appProperties` are only written when we are the one creating it (docs/03).
     */
    private suspend fun folder(
        ctx: StepContext,
        state: DriveUploadState,
        rendered: String,
        base: String,
    ): DriveFile {
        state.folderId?.let { return DriveFile(it, base, null, state.folderWebViewLink) }
        val parent = folders.resolve(rendered)
        val existing = api.findChild(parent, base, DriveApi.FOLDER_MIME)
        if (existing != null) return existing
        return api.createFolder(
            name = base,
            parentId = parent,
            description = ctx.recording.meta.title,
            appProperties = buildMap {
                put("recordingId", ctx.recording.id)
                put("workflowId", ctx.job.workflowId)
            },
        )
    }

    /** Returns the Drive file and the byte count that was uploaded. */
    private suspend fun upload(
        ctx: StepContext,
        state: DriveUploadState,
        key: String,
        folderId: String,
        name: String,
        path: Path,
        mimeType: String,
        md5: String,
        save: (DriveUploadState) -> Unit,
    ): Pair<DriveFile, Long> {
        var current = state

        suspend fun persist(file: UploadState) {
            current = current.with(key, file)
            save(current)
            ctx.saveState(current.toJson())
        }

        // An earlier run uploaded a corrupt file and did not get to delete it. Finish that first,
        // or the find-by-name below would adopt the very file we rejected.
        current.files[key]?.pendingDelete?.let { pending ->
            api.delete(pending)
            persist(UploadState())
        }

        val size = deps.fileSystem.metadata(path).size
            ?: throw StepFailure(retryable = false, reason = CoreMessage.STEP_FAILED.code("cannot size '$name'"))

        already(current.files[key], md5)?.let {
            deps.logger.log(Logger.Level.INFO, "drive.skip", mapOf("name" to name, "fileId" to it.id))
            return it to size
        }
        // Drive allows several children with the same name, and we cannot prove the ones we did
        // not just upload are ours — so pick the one that matches and leave the rest alone.
        api.findChildren(folderId, name).firstOrNull { it.md5 == md5 }?.let {
            persist(UploadState(fileId = it.id))
            deps.logger.log(Logger.Level.INFO, "drive.skip", mapOf("name" to name, "fileId" to it.id))
            return it to size
        }

        val meta = DriveFileMeta(name = name, parents = listOf(folderId), mimeType = mimeType)
        val file = if (size <= api.multipartLimit) {
            api.multipartUpload(meta, deps.fileSystem.read(path) { readByteArray() })
        } else {
            api.uploadResumable(meta, path, size, current.files[key]) { upload -> persist(upload) }
        }
        if (file.md5 != md5) {
            // A file with the wrong content is worse than no file: the next attempt would find it
            // by name. Record the intent to delete before deleting, so a failure here is resumable.
            persist(UploadState(pendingDelete = file.id))
            api.delete(file.id)
            persist(UploadState())
            throw StepFailure(
                retryable = true,
                reason = CoreMessage.STEP_FAILED.code("md5 mismatch for '$name': ${file.md5} != $md5"),
            )
        }
        persist(UploadState(fileId = file.id))
        return file to size
    }

    /** A file the state claims we uploaded, confirmed against Drive rather than trusted. */
    private suspend fun already(saved: UploadState?, md5: String): DriveFile? {
        val fileId = saved?.fileId ?: return null
        val file = DriveFile.from(api.getFile(fileId, ResumableUploadPlanner.FILE_FIELDS)) ?: return null
        return file.takeIf { it.md5 == md5 }
    }

    /** Computed once per part and kept in `part.md5`, so a retry never re-hashes the segment. */
    private suspend fun partMd5(ctx: StepContext, part: Part): String {
        store.md5(ctx.recording.id, part.part, part.track)?.let { return it }
        val md5 = PartHasher.md5(deps.fileSystem, ctx.recording.dir / part.file)
        store.putMd5(ctx.recording.id, part.part, part.track, md5)
        return md5
    }

    private fun partKey(part: Part): String = "p${part.part.toString().padStart(3, '0')}_${part.track.wire}"

    private fun entry(
        part: Int,
        track: String,
        name: String,
        bytes: Long,
        sha256: String,
        file: DriveFile,
    ): JsonObject = buildJsonObject {
        put("part", part)
        put("track", track)
        put("name", name)
        put("bytes", bytes)
        put("sha256", sha256)
        put("fileId", file.id)
        file.webViewLink?.let { put("webViewLink", it) }
    }

    companion object {
        const val TYPE = "drive.upload"
        internal const val PART_MIME = "audio/mp4"
        internal const val META_MIME = "application/json"
        internal const val META_KEY = "meta"

        fun create(db: RecDatabase, deps: CoreDeps): DriveUploadRunner {
            val api = DriveApi(deps)
            val store = DriveStore(db, deps)
            return DriveUploadRunner(api, FolderResolver(api, store, deps), store, deps)
        }
    }
}
