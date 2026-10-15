@file:OptIn(kotlin.time.ExperimentalTime::class)

package app.recly.android.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.recly.android.work.WaveformPrecompute
import app.recly.android.work.WorkScheduler
import recly.core.message.CoreMessage
import recly.core.platform.Logger
import recly.core.recording.ImportResult

/**
 * docs/03 "Naming rules": the files the user picked or shared into Recly, imported one after another —
 * each its own row, which the list shows as `IMPORTING` while it is transcoded. Process-wide, like the
 * model download, so leaving the screen does not cancel an import half done; a process that dies takes
 * its import with it, and the core drops the row at the next start (`RecordingRecovery`).
 */
class AudioImports private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Mutex()
    private val _failure = MutableStateFlow<String?>(null)

    /** Why the last import failed, as a `CoreMessage` code — the list's banner — until it is put away. */
    val failure: StateFlow<String?> = _failure.asStateFlow()

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _failure.value = null
        // Every file is opened here, before this returns, while the grants it came with still hold — a share's may
        // end with the screen that received it, and a stream already open outlives it. The copies are made in the
        // background, one after another; only the transcoding waits its turn: each copy joins the queue as it
        // lands, and the mutex serves its waiters in order.
        val opened = uris.map { runCatching { open(it) } }
        scope.launch {
            opened.forEach { result ->
                val source = result.getOrElse { failed(it); return@forEach }
                val staged = stage(source) ?: return@forEach
                scope.launch { queue.withLock { importOne(staged) } }
            }
        }
    }

    fun dismissFailure() {
        _failure.value = null
    }

    /** A picked or shared file, opened while its grant holds, with the name and date its provider gave it. */
    private class Source(val input: InputStream, val name: String, val date: Instant?)

    /** A file copied into the cache, with the name and date its provider gave it. */
    private class Staged(val copy: File, val name: String, val date: Instant?)

    private fun open(uri: Uri): Source {
        val input = context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return Source(input, displayName(uri), fileDate(uri))
    }

    /**
     * The core reads a path, and a picked or shared file is a `content:` URI — so it is copied into the cache,
     * from the stream [open] took while the grant held. Null when it could not be read, having said so.
     */
    private suspend fun stage(source: Source): Staged? {
        val copy = File(context.cacheDir, "$IMPORTS/${UUID.randomUUID()}")
        return try {
            copy.parentFile!!.mkdirs()
            source.input.use { input -> copy.outputStream().use { input.copyTo(it) } }
            Staged(copy, source.name, source.date)
        } catch (e: Exception) {
            failed(e)
            copy.delete()
            null
        }
    }

    private suspend fun failed(e: Throwable) {
        CoreModule.get(context).core.deps.logger.log(Logger.Level.WARN, "rec.import.failed", mapOf("stage" to "copy"), e)
        _failure.value = CoreMessage.IMPORT_UNREADABLE.code(detail = e.message ?: e::class.simpleName)
    }

    /** One staged file into a recording; the copy goes either way. */
    private suspend fun importOne(staged: Staged) {
        val core = CoreModule.get(context).core
        try {
            when (val result = core.importAudio(staged.copy.path, staged.name, staged.date, AndroidAudioImporter(Dispatchers.Default))) {
                is ImportResult.Imported -> {
                    // Queued by the core like a stopped recording: the scheduler is woken the way the
                    // recorder's stop wakes it, and the waveform is drawn ahead of the first open.
                    WorkScheduler(context).onJobsDue()
                    WaveformPrecompute.request(core, result.recordingId)
                }
                is ImportResult.Failed -> _failure.value = result.reason
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            core.deps.logger.log(Logger.Level.WARN, "rec.import.failed", mapOf("stage" to "import"), e)
            _failure.value = CoreMessage.IMPORT_UNREADABLE.code(detail = e.message ?: e::class.simpleName)
        } finally {
            staged.copy.delete()
        }
    }

    /** What the provider calls the file — its name is the recording's title (docs/03). */
    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    /**
     * D27: the file's own date — the recording starts then, as a voice memo from last week starts last week. All
     * columns are asked for, because a provider throws on one it does not have and each names its own; a provider
     * that offers neither (a share through a `FileProvider` gives only the name and size) leaves the core's "now".
     */
    private fun fileDate(uri: Uri): Instant? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            fun long(column: String): Long? =
                cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
            importDate(
                createdMs = long(MediaStore.MediaColumns.DATE_TAKEN),
                modifiedMs = long(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                modifiedSec = long(MediaStore.MediaColumns.DATE_MODIFIED),
            )
        }
    }.getOrNull()

    companion object {
        private const val IMPORTS = "import"

        @Volatile private var instance: AudioImports? = null

        fun get(context: Context): AudioImports = instance ?: synchronized(this) {
            instance ?: AudioImports(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * D27: an imported file starts at its creation date when there is one, else at its modification date. Android
 * providers have no creation date of their own; MediaStore's `datetaken` — the media's own — is the nearest, and
 * the modification date comes as milliseconds from a document provider or as seconds from MediaStore. Zero or
 * below is no date at all.
 */
internal fun importDate(createdMs: Long?, modifiedMs: Long?, modifiedSec: Long?): Instant? =
    (createdMs?.takeIf { it > 0 } ?: modifiedMs?.takeIf { it > 0 } ?: modifiedSec?.takeIf { it > 0 }?.times(1000))
        ?.let(Instant::fromEpochMilliseconds)
