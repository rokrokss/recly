@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.recly.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.floor
import app.recly.android.ui.theme.LocalReduceMotion
import app.recly.android.ui.theme.Motion
import app.recly.android.ui.theme.Radius
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.BackHandler
import android.os.SystemClock
import androidx.compose.ui.text.font.FontFamily
import app.recly.android.ui.component.Glyph
import app.recly.android.ui.component.GlyphButton
import app.recly.android.ui.component.LoadingText
import app.recly.android.ui.theme.doneBadgeMs
import app.recly.android.ui.theme.processingHoldMs
import kotlinx.coroutines.launch
import recly.core.processing.ProcessingTranscription
import recly.core.recording.ExportFormat
import recly.core.recording.SilenceRanges
import recly.core.recording.WaveformPeaks
import recly.core.transcribe.EditResult
import recly.core.transcribe.RetranscribeResult
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptEdit
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.ScreenHeader
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import kotlinx.coroutines.delay
import recly.core.transcribe.TranscriptAvailability

/**
 * What the detail can do beyond reading — the More menu, Share, the editor, playback speed and the
 * highlights — and this device's playback preferences. [MainActivity] builds it from [JobsViewModel].
 */
class DetailActions(
    val playbackSpeed: Float = 1f,
    val skipSilence: Boolean = false,
    /** The saved processing settings' transcription: what "Transcribe again" runs, null while unread. */
    val transcription: ProcessingTranscription? = null,
    val onSpeed: (Float) -> Unit = {},
    val onSkipSilence: (Boolean) -> Unit = {},
    val onHighlights: (List<Double>) -> Unit = {},
    val onExport: suspend (ExportFormat) -> String? = { null },
    val onEdit: suspend (TranscriptEdit) -> EditResult = { EditResult.NoTranscript },
    val onRetranscribe: suspend () -> RetranscribeResult? = { null },
    val onCloseFind: () -> Unit = {},
)

/**
 * docs/08 "Result files", deliverable 3: what the transcribe step wrote, as the speaker turns it is made
 * of. Reading it is [JobsViewModel]'s: this draws what came back and knows nothing about where it
 * came from.
 *
 * It is a page behind a ledger row rather than a tab of its own (docs/09 screen principle 2), so the header
 * carries the way back — and, at its end, Share and More (docs/09 "Detail header and More menu").
 */
@Composable
fun RecordingDetailScreen(
    detail: DetailState,
    onClose: () -> Unit,
    onRename: (String) -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
    actions: DetailActions = DetailActions(),
) {
    // One player per recording: opening another one releases the one this was playing, and so does
    // leaving the page. Nothing keeps playing behind a screen nobody is looking at.
    val context = LocalContext.current
    val player = remember(detail.recordingId) { RecordingPlayer(context) }
    DisposableEffect(player) { onDispose { player.release() } }
    // The clock is wound from here, and only while something is playing (docs/09 "Motion": nothing
    // moves that is not saying something).
    LaunchedEffect(player, player.isPlaying) {
        while (player.isPlaying) {
            player.tick()
            delay(RecordingPlayer.TICK_MS)
        }
    }
    // The microphone belongs to the recorder while it holds it, and a recording can be started from
    // somewhere else entirely — a tile, the widget, an intent. Taking Play off the bar is not enough
    // then: what is already playing would play on into the capture, so it is stopped here.
    LaunchedEffect(player, detail.deviceRecording) {
        if (detail.deviceRecording) player.stop()
    }
    // docs/09 "Playback": this device's speed, and the silences Skip silence jumps — worked out from the
    // waveform's own peaks, the same on every shell.
    LaunchedEffect(player, actions.playbackSpeed) { player.setSpeed(actions.playbackSpeed) }
    val silences = remember(detail.waveform, actions.skipSilence) {
        if (actions.skipSilence && detail.waveform.isNotEmpty()) SilenceRanges.compute(detail.waveform.asList(), WaveformPeaks.WINDOW_SEC) else emptyList()
    }
    LaunchedEffect(player, silences) { player.silences = silences }

    // docs/03 "Titles": whether the dialog that renames this recording is up. Keyed on the recording,
    // so a page that becomes another one is not left asking about the title of the one before it.
    var renaming by remember(detail.recordingId) { mutableStateOf(false) }
    if (renaming) {
        RenameDialog(
            title = detail.title,
            onSave = {
                renaming = false
                onRename(it)
            },
            onCancel = { renaming = false },
        )
    }
    var sharing by remember(detail.recordingId) { mutableStateOf(false) }
    if (sharing) ShareSheet(detail, actions.onExport, onDismiss = { sharing = false })
    var askAgain by remember(detail.recordingId) { mutableStateOf(false) }
    // Why a "Transcribe again" the core refused did not start, for a moment under the header.
    var refusal by remember(detail.recordingId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(refusal) { if (refusal != null) { delay(REFUSAL_MS); refusal = null } }
    val scope = rememberCoroutineScope()
    val transcription = actions.transcription
    if (askAgain && transcription != null) {
        val transcript = detail.transcript
        RetranscribeDialog(
            transcription,
            edited = transcript != null && (transcript.editedAt != null || transcript.speakers.any { it.name != null }),
            onConfirm = {
                askAgain = false
                scope.launch { refusal = actions.onRetranscribe()?.let(::retranscribeRefusal) }
            },
            onCancel = { askAgain = false },
        )
    }

    // docs/09 "Editing and speakers": the editor's draft, while it is open.
    var draft by remember(detail.recordingId) { mutableStateOf<EditDraft?>(null) }
    var discarding by remember(detail.recordingId) { mutableStateOf(false) }
    var saving by remember(detail.recordingId) { mutableStateOf(SavePhase.IDLE) }
    val leaveEditor = { if (draft?.changed == true) discarding = true else draft = null }
    BackHandler(enabled = draft != null) { leaveEditor() }
    if (discarding) DiscardDialog(onKeep = { discarding = false }, onDiscard = { discarding = false; draft = null })
    val save = save@{
        val editing = draft ?: return@save
        if (!editing.changed) {
            draft = null
            return@save
        }
        saving = SavePhase.SAVING
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            val result = actions.onEdit(editing.edit())
            val work = SystemClock.elapsedRealtime() - started
            delay(processingHoldMs(work))
            if (result is EditResult.Edited) {
                saving = SavePhase.DONE
                delay(doneBadgeMs(work))
                draft = null
            }
            saving = SavePhase.IDLE
        }
    }

    // docs/09 "Transcript reader": following the playhead, until the user scrolls the transcript.
    var following by remember(detail.recordingId) { mutableStateOf(true) }
    LaunchedEffect(player.isPlaying) { if (!player.isPlaying) following = true }

    // docs/09 "Search": the find bar of a detail opened from a search.
    val transcript = detail.transcript
    val groups = remember(transcript) { transcript?.let(::readerGroups).orEmpty() }
    val matches = remember(groups, detail.find?.query) { detail.find?.let { findMatches(groups, it.query) }.orEmpty() }
    var current by remember(detail.recordingId, matches) {
        val at = detail.find?.atSec
        val group = if (at == null) 0 else activeGroup(groups, at).coerceAtLeast(0)
        mutableIntStateOf(matches.indexOfFirst { it.group >= group }.takeIf { it >= 0 } ?: if (matches.isEmpty()) -1 else 0)
    }

    // docs/09 "Editing and speakers": the reading page's speaker menu, its changes saved at once.
    var speakerMenu by remember(detail.recordingId) { mutableStateOf<ReaderGroup?>(null) }
    // The speaker being named, and the group whose badge asked — where `Saving…` is said.
    var speakerNaming by remember(detail.recordingId) { mutableStateOf<Pair<String, Int>?>(null) }
    var savingGroup by remember(detail.recordingId) { mutableStateOf<Pair<Int, SavePhase>?>(null) }
    // One change at a time: an edit is read-modify-write, so the speaker actions wait until this one has saved.
    val saveNow: (Int, TranscriptEdit) -> Unit = saveNow@{ group, edit ->
        if (savingGroup != null) return@saveNow
        savingGroup = group to SavePhase.SAVING
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            val result = actions.onEdit(edit)
            val work = SystemClock.elapsedRealtime() - started
            delay(processingHoldMs(work))
            if (result is EditResult.Edited) {
                savingGroup = group to SavePhase.DONE
                delay(doneBadgeMs(work))
            }
            savingGroup = null
        }
    }
    speakerNaming?.let { (id, group) ->
        SpeakerNameDialog(transcript?.speakers?.firstOrNull { it.id == id }?.name, onSave = { name ->
            speakerNaming = null
            saveNow(group, TranscriptEdit.RenameSpeaker(id, name))
        }, onCancel = { speakerNaming = null })
    }
    var highlightMenu by remember(detail.recordingId) { mutableStateOf<Double?>(null) }
    val canSeek = !detail.writing && !detail.deviceRecording && !detail.audio.isEmpty &&
        detail.driveFetch != DriveFetch.DECIDING && detail.driveFetch != DriveFetch.FETCHING
    val seek: (Double) -> Unit = { if (!detail.deviceRecording) player.seek(detail.audio, it) }

    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(modifier = modifier.fillMaxSize()) {
        // The title alone: the recording's id is the ledger's key, not something the user reads by.
        if (!keyboardVisible) {
            if (draft != null) {
                ScreenHeader(title = stringResource(R.string.detail_edit))
            } else ScreenHeader(
                title = detail.title ?: stringResource(R.string.jobs_untitled),
                trailingAlignment = Alignment.TopEnd,
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        if (!detail.loading) {
                            GlyphButton(Glyph.SHARE, stringResource(R.string.detail_share), { sharing = true }, Modifier.testTag("detail-share"))
                        }
                        // Not while the recorder is still writing into this take: the core refuses to
                        // rename or edit one, and an action that does nothing is not one to offer. Not
                        // before the load has said which of the two this is, either.
                        if (!detail.loading && !detail.writing) {
                            MoreButton(detail, transcription, player.positionSec, MoreActions(
                                onRename = { renaming = true },
                                onEdit = { transcript?.let { draft = EditDraft.of(it) } },
                                onRetranscribe = { askAgain = true },
                                onAddHighlight = { actions.onHighlights(detail.highlights + player.positionSec) },
                            ))
                        }
                        BlueprintButton(
                            label = stringResource(R.string.action_close),
                            onClick = onClose,
                            modifier = Modifier.testTag("detail-close"),
                            tone = ButtonTone.QUIET,
                            minWidth = MinTouch,
                        )
                    }
                },
            )
        }
        HairLine()
        refusal?.let {
            Text(stringResource(it), Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s).testTag("detail-refusal"),
                style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
            HairLine()
        }

        // docs/09 screen principle 2: only the transcript scrolls; playback stays above the tab bar.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val editing = draft
            when {
                editing != null -> TranscriptEditor(editing, { draft = it }, canSeek, seek, Modifier.fillMaxSize(), detail.audio.totalSec)
                detail.loading -> Notice(stringResource(R.string.detail_loading))
                transcript == null -> Notice(
                    stringResource(detail.availability.message()),
                    onRetry = onReload.takeIf { detail.availability == TranscriptAvailability.UNAVAILABLE },
                )
                transcript.segments.none { it.text.isNotBlank() } ->
                    Notice(stringResource(R.string.detail_transcript_empty))
                else -> Column(Modifier.fillMaxSize()) {
                    // docs/09 "Transcript reader": the old text stays while the new one is made.
                    if (detail.retranscribing) {
                        LoadingText(
                            stringResource(if (detail.retranscribingLocally) R.string.processing_local_running else R.string.reader_transcribing_again),
                            MaterialTheme.typography.bodySmall, blueprint.textMuted,
                            Modifier.padding(horizontal = Space.m, vertical = Space.s).testTag("retranscribing"),
                        )
                        HairLine()
                    }
                    if (detail.find != null) {
                        FindBar(current, matches.size,
                            onPrevious = { if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size },
                            onNext = { if (matches.isNotEmpty()) current = (current + 1) % matches.size },
                            onClose = actions.onCloseFind)
                    }
                    TranscriptReader(
                        transcript = transcript,
                        seekableDurationSec = detail.audio.totalSec,
                        canSeek = canSeek,
                        onSeek = seek,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        positionSec = player.positionSec,
                        playing = player.isPlaying,
                        highlights = detail.highlights,
                        onHighlight = { highlightMenu = it },
                        highlightMenuFor = highlightMenu,
                        highlightMenu = {
                            highlightMenu?.let { at ->
                                HighlightMenu(at, onGo = { seek(at) }, onRemove = { actions.onHighlights(detail.highlights - at) },
                                    onDismiss = { highlightMenu = null })
                            }
                        },
                        onSpeaker = { speakerMenu = it },
                        speakersEnabled = savingGroup == null,
                        speakerMenuFor = speakerMenu?.index,
                        speakerMenu = {
                            speakerMenu?.let { group ->
                                SpeakerMenu(transcript, group.speaker,
                                    onRename = { speakerNaming = it to group.index },
                                    onChange = { id -> saveNow(group.index, speakerChange(transcript, group.segments, id)) },
                                    onDismiss = { speakerMenu = null })
                            }
                        },
                        savingGroup = savingGroup?.first,
                        savingLabel = savingGroup?.second?.let { stringResource(if (it == SavePhase.DONE) R.string.action_done else R.string.edit_saving) },
                        find = matches,
                        findCurrent = current,
                        following = following,
                        onFollowChange = { following = it },
                        startAt = detail.find?.atSec?.let { activeGroup(groups, it) },
                    )
                }
            }
            if (draft == null && player.isPlaying && !following) {
                BackToPlayback({ following = true }, Modifier.align(Alignment.BottomCenter).padding(bottom = Space.s))
            }
        }

        // docs/09 "Editing and speakers": the editor's footer and its answers sit under the fields, above
        // the keyboard — the note says what a save does to the files in storage.
        draft?.let { editing ->
            HairLine()
            Column(Modifier.fillMaxWidth().background(blueprint.surface).padding(horizontal = Space.m, vertical = Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(stringResource(if (detail.folder) R.string.edit_footer else R.string.edit_footer_agent),
                    style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
                    BlueprintButton(stringResource(R.string.action_cancel), { leaveEditor() }, tone = ButtonTone.QUIET, minWidth = MinTouch,
                        enabled = saving == SavePhase.IDLE)
                    BlueprintButton(
                        label = when {
                            saving == SavePhase.SAVING -> stringResource(R.string.edit_saving)
                            !editing.changed -> stringResource(R.string.job_state_done)
                            else -> stringResource(R.string.action_save)
                        },
                        onClick = save,
                        tone = ButtonTone.PRIMARY,
                        enabled = saving == SavePhase.IDLE,
                        leading = if (saving == SavePhase.DONE) stringResource(R.string.action_done) else null,
                        modifier = Modifier.testTag("edit-save"),
                    )
                }
            }
        }

        // A take still being written to has nothing whole to play yet, and nothing to say about it.
        if (!keyboardVisible && !detail.loading && !detail.writing) {
            HairLine()
            PlayerBar(detail, player, actions)
        }
    }
}

/** A save's window, shown on its button or beside a badge: `Saving…`, then `✓` (docs/09 trend 2). */
internal enum class SavePhase { IDLE, SAVING, DONE }

/** docs/09 "Detail header and More menu": a refused "Transcribe again" in the menu's own reasons; null when it started. */
internal fun retranscribeRefusal(result: RetranscribeResult): Int? = when (result) {
    is RetranscribeResult.Started -> null
    RetranscribeResult.Busy -> R.string.detail_transcribing
    RetranscribeResult.NoAudio -> R.string.player_no_audio
    RetranscribeResult.NoTranscriptionConfigured -> R.string.detail_transcription_off
    RetranscribeResult.Unsupported -> R.string.detail_not_uploaded
}

/** How long a refusal stays under the header. */
private const val REFUSAL_MS = 4_000L

/**
 * "Change speaker for this line" on a reading group: every segment of it to [speakerId], or to a new
 * speaker — which the core numbers `S{n+1}` on the first segment, and the rest then name.
 */
internal fun speakerChange(transcript: Transcript, segments: IntRange, speakerId: String?): TranscriptEdit {
    val id = speakerId ?: "S${(transcript.speakers.mapNotNull { it.id.removePrefix("S").toIntOrNull() }.maxOrNull() ?: 0) + 1}"
    return TranscriptEdit.Batch(segments.mapIndexed { i, segment ->
        TranscriptEdit.SetSpeaker(segment, if (i == 0 && speakerId == null) null else id)
    })
}

/**
 * docs/03 "Titles": the recording's name, asked again. The same question the end of a recording asks
 * (`RecordingScreen`'s `TitleDialog`) without the second half of it — how many people were in the
 * room is a hint the transcribe step has long since used by the time this page exists.
 *
 * Empty is an answer: it clears the title back to the timestamp name (docs/09 screen principle 5 — title,
 * one line under it, two buttons).
 */
@Composable
private fun RenameDialog(title: String?, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(title.orEmpty()) }
    BlueprintDialog(
        title = stringResource(R.string.recording_title_prompt),
        onDismissRequest = onCancel,
        actions = {
            BlueprintButton(
                label = stringResource(R.string.action_cancel),
                onClick = onCancel,
                tone = ButtonTone.QUIET,
                minWidth = MinTouch,
            )
            BlueprintButton(
                label = stringResource(R.string.recording_title_save),
                onClick = { onSave(text) },
                modifier = Modifier.testTag("rename-save"),
                tone = ButtonTone.PRIMARY,
            )
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.recording_title_field)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        )
    }
}

/**
 * docs/08 "Result files" · docs/09 screen principle 2: the recording itself, where this phone still has it. Its
 * shape on top, with the playhead moving across it and a drag on it to move where the playhead is,
 * and the recording's own clock and play button under that. The bar stays at the bottom of the
 * detail, with the primary action on the right, within reach while reading the transcript.
 */
@Composable
private fun PlayerBar(detail: DetailState, player: RecordingPlayer, actions: DetailActions) {
    val palette = blueprint
    // Where the finger is while it is on the waveform, and null the rest of the time. The playhead
    // and the clock follow it rather than the player: the seek happens when the finger lets go, and
    // a bar that only moved then would not be a scrub.
    var scrubSec by remember(detail.audio) { mutableStateOf<Double?>(null) }
    // docs/09 "Highlights": the tick whose Go to / Remove menu is open.
    var tickMenu by remember(detail.recordingId) { mutableStateOf<Double?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        // While the parts are coming back from Drive the row is already there, loading, so the
        // bars arrive in place rather than the page growing a row when they do — and, having come
        // from Drive, they grow in once where the loader was.
        val shown = !detail.audio.isEmpty || detail.driveFetch == DriveFetch.FETCHING
        var fetched by remember(detail.recordingId) { mutableStateOf(false) }
        LaunchedEffect(detail.driveFetch) { if (detail.driveFetch == DriveFetch.FETCHING) fetched = true }
        val waveform: @Composable () -> Unit = {
            when (waveformSlot(detail)) {
                WaveformSlot.FETCHING -> WaveformLoader()
                // The peaks are being read or decoded: the loader, and never a flat line that
                // would read as a silent recording. Play does not need them and stays as it is.
                WaveformSlot.LOADING -> WaveformLoader(label = stringResource(R.string.player_waveform_loading))
                // No seek while this phone is recording, as the transcript's times allow none: the
                // microphone is the recorder's, and the player was stopped for it.
                WaveformSlot.WAVEFORM -> Box {
                    Waveform(detail.audio, detail.waveform, scrubSec ?: player.positionSec,
                        onScrub = { scrubSec = it }, onSeek = { if (!detail.deviceRecording) player.seek(detail.audio, it) }, growIn = fetched,
                        highlights = detail.highlights, onHighlight = { tickMenu = it },
                        onRemoveHighlight = { at -> actions.onHighlights(detail.highlights - at) })
                    tickMenu?.let { at ->
                        HighlightMenu(at, onGo = { if (!detail.deviceRecording) player.seek(detail.audio, at) },
                            onRemove = { actions.onHighlights(detail.highlights - at) }, onDismiss = { tickMenu = null })
                    }
                }
            }
        }
        if (LocalConfiguration.current.screenHeightDp < 480 && shown) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { waveform() }
                Box(Modifier.weight(2f)) { PlayerControls(detail, player, scrubSec, actions) }
            }
        } else {
            if (shown) waveform()
            PlayerControls(detail, player, scrubSec, actions)
        }
        if (player.failed) Text(stringResource(R.string.player_error), style = MaterialTheme.typography.bodyMedium, color = palette.danger)
    }
}

/**
 * docs/09 screen principle 2: the recording as a shape, and the one place on this page a second of it can
 * be pointed at. The drag is on the whole row, so a tap anywhere in it is a seek — and playback is
 * not interrupted by either, because what a scrub is for is hearing another part of the same take.
 *
 * @param peaks the recording's own timeline, or empty until the decode is through — the row keeps
 *   its height and its playhead either way, so the bar does not change shape when the peaks arrive.
 * @param onScrub where the finger is, while it is down, and null when it lets go.
 * @param growIn the bars rise out of the centre line once, left first, when the peaks arrive — for
 *   a recording that has just come back from Drive, where the loader stood a moment ago.
 */
@Composable
internal fun Waveform(
    audio: RecordingPlaylist.Selection,
    peaks: FloatArray,
    positionSec: Double,
    onScrub: (Double?) -> Unit,
    onSeek: (Double) -> Unit,
    growIn: Boolean = false,
    /** docs/09 "Highlights": the marks, drawn as accent ticks with a square cap; a tap near one opens its menu. */
    highlights: List<Double> = emptyList(),
    onHighlight: (Double) -> Unit = {},
    onRemoveHighlight: (Double) -> Unit = {},
) {
    val palette = blueprint
    val hair = palette.line
    val totalSec = audio.totalSec
    val reduce = LocalReduceMotion.current
    val reveal = remember(audio) { Animatable(if (growIn && !reduce) 0f else 1f) }
    LaunchedEffect(audio, peaks.isNotEmpty()) {
        if (peaks.isNotEmpty() && reveal.value < 1f) reveal.animateTo(1f, tween(GROW_MS, easing = LinearEasing))
    }
    var focused by remember { mutableStateOf(false) }
    // The drag below outlives the composition it started in (it is keyed on the recording alone),
    // so it calls whatever the caller passed last — a seek refused since then stays refused.
    val scrub by rememberUpdatedState(onScrub)
    val seek by rememberUpdatedState(onSeek)
    val marks by rememberUpdatedState(highlights)
    val mark by rememberUpdatedState(onHighlight)
    val slop = LocalViewConfiguration.current.touchSlop
    val near = with(LocalDensity.current) { TICK_REACH.toPx() }
    // docs/09 Accessibility: the row reports itself as the recording's position, and a reader that cannot
    // see the shape moves the playhead by setting it — and hears where it is as the clock beside
    // it says it, because that is what the playhead is.
    val label = stringResource(R.string.player_position)
    val stamp = hms(positionSec.toLong())
    Box(Modifier.fillMaxWidth()) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(MinTouch)
            .testTag("waveform")
            .border(if (focused) 2.dp else 0.dp, if (focused) palette.accent else androidx.compose.ui.graphics.Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                contentDescription = label
                stateDescription = stamp
                progressBarRangeInfo =
                    ProgressBarRangeInfo(positionSec.toFloat(), 0f..totalSec.toFloat().coerceAtLeast(0f))
                setProgress { target ->
                    onSeek(target.toDouble())
                    true
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> onSeek((positionSec - 5.0).coerceAtLeast(0.0))
                    Key.DirectionRight -> onSeek((positionSec + 5.0).coerceAtMost(totalSec))
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusable()
            // Keyed on the recording and not on its seconds: what a release seeks is the selection
            // this gesture was composed with, and two recordings can be the same length.
            .pointerInput(audio) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var sec = second(down.position.x, size.width, totalSec)
                    scrub(sec)
                    down.consume()
                    var pressed = true
                    var moved = false
                    while (pressed) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        sec = second(change.position.x, size.width, totalSec)
                        scrub(sec)
                        change.consume()
                        pressed = change.pressed
                        if (kotlin.math.abs(change.position.x - down.position.x) > slop) moved = true
                    }
                    // A tap on a tick is a question about that mark, not a seek beside it.
                    val tick = if (moved || totalSec <= 0) null else marks.minByOrNull { kotlin.math.abs(it / totalSec * size.width - down.position.x) }
                        ?.takeIf { kotlin.math.abs(it / totalSec * size.width - down.position.x) <= near }
                    scrub(null)
                    if (tick != null) mark(tick) else seek(sec)
                }
            },
    ) {
        val playhead = if (totalSec > 0) (size.width * positionSec / totalSec).toFloat() else 0f
        val step = WaveformStep.toPx()
        val line = hair.toPx()
        val bins = RecordingWaveform.bins(peaks, (size.width / step).toInt())
        // docs/09 "Lines": straight bars of one width on one gap, no caps and no gradient. Behind the
        // playhead is the accent and ahead of it the muted colour, both at full opacity — docs/09
        // "Accessibility" asks 3:1 of a graphic, and the muted token faded out to hint at "not played
        // yet" is under 2:1 on the surface.
        //
        // Nothing decoded yet (or a decode that failed) is one hairline across the middle: the row
        // keeps its height and its playhead, so the bar does not change shape when the peaks arrive.
        if (bins.isEmpty()) {
            drawRect(
                color = palette.grid,
                topLeft = Offset(0f, (size.height - line) / 2),
                size = Size(size.width, line),
            )
        }
        val shown = reveal.value
        bins.forEachIndexed { index, bin ->
            val x = index * step
            // Left first: each bar starts a little after the one before it and rises in 40% of the time.
            val start = GROW_SPREAD * index / maxOf(1, bins.size - 1)
            val k = ((shown - start) / (1f - GROW_SPREAD)).coerceIn(0f, 1f)
            val rise = 1f - (1f - k) * (1f - k) * (1f - k)
            // Silence is a tick rather than nothing, so the row reads as the whole recording.
            val height = maxOf(WaveformMinBar.toPx(), bin * size.height * rise)
            drawRect(
                color = if (x <= playhead) palette.accent else palette.textMuted,
                topLeft = Offset(x, (size.height - height) / 2),
                size = Size(WaveformBar.toPx(), height),
            )
        }
        // Above the bars, under the playhead: a 2dp tick over the whole height with a 6dp square cap at its top.
        if (totalSec > 0) highlights.forEach { at ->
            val x = (size.width * at / totalSec).toFloat().coerceIn(0f, size.width - TICK.toPx())
            drawRect(palette.accent, topLeft = Offset(x, 0f), size = Size(TICK.toPx(), size.height))
            val cap = TICK_CAP.toPx()
            drawRect(palette.accent, topLeft = Offset((x + TICK.toPx() / 2 - cap / 2).coerceIn(0f, size.width - cap), 0f), size = Size(cap, cap))
        }
        drawRect(
            color = palette.accent,
            topLeft = Offset(playhead.coerceIn(0f, size.width - line), 0f),
            size = Size(line, size.height),
        )
    }
    // docs/09 "Highlights": each tick is an element of its own for a screen reader, with Go to and Remove.
    if (totalSec > 0 && highlights.isNotEmpty()) {
        val remove = stringResource(R.string.highlight_remove)
        val goLabels = highlights.map { stringResource(R.string.transcript_seek, hms(it.toLong())) }
        val names = highlights.map { stringResource(R.string.highlight_tick, hms(it.toLong())) }
        BoxWithConstraints(Modifier.matchParentSize()) {
            highlights.forEachIndexed { index, at ->
                Box(
                    Modifier
                        .offset(x = maxWidth * (at / totalSec).toFloat() - TICK_REACH)
                        .size(TICK_REACH * 2, MinTouch)
                        .clearAndSetSemantics {
                            contentDescription = names[index]
                            customActions = listOf(
                                CustomAccessibilityAction(goLabels[index]) { onSeek(at); true },
                                CustomAccessibilityAction(remove) { onRemoveHighlight(at); true },
                            )
                        },
                )
            }
        }
    }
    }
}

/** docs/09 "Highlights": the tick, its square cap, and how near a tap must be to mean it. */
private val TICK: Dp = 2.dp
private val TICK_CAP: Dp = 6.dp
private val TICK_REACH: Dp = 12.dp

/** What the player bar's waveform row holds (see [PlayerBar]). */
internal enum class WaveformSlot {
    /** No part is here yet — the trip to Drive is under way; the fetch's progress speaks for it. */
    FETCHING,

    /** The parts are here and their peaks are on the way. */
    LOADING,

    WAVEFORM,
}

internal fun waveformSlot(detail: DetailState): WaveformSlot = when {
    detail.audio.isEmpty -> WaveformSlot.FETCHING
    detail.waveformLoading -> WaveformSlot.LOADING
    else -> WaveformSlot.WAVEFORM
}

/**
 * docs/09 "Motion": the waveform row while the recording comes back from Drive, or while its peaks are
 * read or decoded — short ghost ticks where the bars will be, and a hard-edged band of ten that
 * steps across them left to right, one bar a frame at 30 fps. It does not rise and fall or flow the
 * way a playing or recording waveform does, and it leaves nothing filled behind it the way a
 * playhead does. With reduce motion the band stays off and the ticks stand still.
 *
 * [label] is what a screen reader hears; null for the Drive fetch, whose progress beside it says it
 * ([PlayerControls]).
 */
@Composable
private fun WaveformLoader(label: String? = null) {
    val palette = blueprint
    val reduce = LocalReduceMotion.current
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(reduce) {
        while (!reduce) {
            delay(LOADER_FRAME_MS)
            frame++
        }
    }
    Canvas(Modifier.fillMaxWidth().height(MinTouch).testTag("waveform-loader").clearAndSetSemantics { label?.let { contentDescription = it } }) {
        val step = WaveformStep.toPx()
        val count = (size.width / step).toInt()
        val head = if (reduce) -1 else frame % (count + LOADER_BAND)
        val tick = size.height * LOADER_TICK
        for (index in 0 until count) {
            val lit = index in head - LOADER_BAND + 1..head
            drawRect(
                color = if (lit) palette.textMuted else palette.grid,
                topLeft = Offset(index * step, (size.height - tick) / 2),
                size = Size(WaveformBar.toPx(), tick),
            )
        }
    }
}

/**
 * docs/09: how far the trip to Drive is, in the place and the shape of the Play button it becomes —
 * the button's own outline, filling with the button's own colour, so that when it is full it is the
 * button. It is sized by the Play label it will carry, so nothing moves when it becomes Play, and
 * the percentage sits in it in the accent — in the button's own ink where the fill has reached it —
 * so it never reads as an empty, disabled button. A screen reader hears the bar's sentence and the
 * percentage.
 */
@Composable
private fun FetchProgress(fraction: Float, folder: Boolean) {
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    val label = stringResource(fetchingLabel(folder))
    val shown by animateFloatAsState(fraction, if (LocalReduceMotion.current) snap() else tween(Motion.STANDARD_MS, easing = Motion.Standard))
    // docs/09 "Typography": a count of bytes is data, so it is a monospace stamp — the same `n%` in every
    // language, as the iPhone writes it.
    val percent = "${floor(fraction * 100.0).toInt()}%"
    val percentStyle = mono.bodySmall
    val style = MaterialTheme.typography.labelLarge
    Box(
        Modifier
            // BlueprintButton's own minimums, with the Play button's on top of them.
            .sizeIn(minWidth = PlayMinWidth, minHeight = PlayMinHeight)
            .defaultMinSize(minWidth = MinTouch, minHeight = MinTouch)
            .clip(shape)
            .border(palette.line, palette.accent, shape)
            .drawBehind { drawRect(palette.accent, size = Size(size.width * shown, size.height)) }
            .clearAndSetSemantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.player_play),
            modifier = Modifier.padding(horizontal = Space.s, vertical = Space.xs),
            style = style,
            color = Color.Transparent,
            maxLines = 3,
        )
        Text(percent, style = percentStyle, color = palette.accent, maxLines = 1)
        Box(
            Modifier
                .matchParentSize()
                .drawWithContent { clipRect(right = size.width * shown) { this@drawWithContent.drawContent() } },
            contentAlignment = Alignment.Center,
        ) {
            Text(percent, style = percentStyle, color = palette.onAccent, maxLines = 1)
        }
    }
}

/** The Play button's size, which the fetch's progress takes before it becomes that button. */
private val PlayMinWidth: Dp = 120.dp
private val PlayMinHeight: Dp = 48.dp

/** docs/09: a tenth of the row's width at a time, slow enough to read as work and not as sound. */
private const val LOADER_BAND = 10
private const val LOADER_FRAME_MS = 33L
/** The ghost ticks' height, as a share of the row. */
private const val LOADER_TICK = 0.3f
/** The bars' rise when a recording arrives from Drive: 750 ms in all, the last bar starting at 60%. */
private const val GROW_MS = 750
private const val GROW_SPREAD = 0.6f

/**
 * docs/09 screen principle 2 · "Spacing": the waveform row's own rhythm. A 2dp bar on a 1dp gap, so how many
 * bars there are is however many 3dp columns the row is wide — the shape is the recording's, and
 * the number of bars is the screen's.
 */
private val WaveformBar: Dp = 2.dp
private val WaveformStep: Dp = 3.dp

/** A bin with no sound in it, so that silence is still part of the timeline. */
private val WaveformMinBar: Dp = 1.dp

/** Where on the recording's clock a point of the row is. */
private fun second(x: Float, width: Int, totalSec: Double): Double =
    if (width <= 0) 0.0 else (x / width).toDouble().coerceIn(0.0, 1.0) * totalSec

/**
 * docs/08 "Result files": one button and the recording's own clock, or the one sentence there is to say
 * instead of them.
 */
@Composable
private fun PlayerControls(detail: DetailState, player: RecordingPlayer, scrubSec: Double?, actions: DetailActions) {
    val palette = blueprint
    when {
        // docs/03 ADR-017: where the clock is, because it is what the clock is instead of. No
        // Play either — there is nothing whole to play until the parts are back.
        // docs/03 ADR-017: the button's place holds how far the trip is; the words are only for
        // reduce motion, where the waveform row above has stopped saying it.
        detail.driveFetch == DriveFetch.FETCHING -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (LocalReduceMotion.current) {
                    Text(stringResource(fetchingLabel(detail.folder)), style = MaterialTheme.typography.bodyMedium, color = palette.textMuted)
                }
            }
            FetchProgress(detail.fetchProgress, detail.folder)
        }

        !detail.audio.isEmpty -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // docs/07 rule 4: a clock is a stamp, not a sentence. The finger while there is one on
            // the waveform, and the player the rest of the time — the two are the same playhead.
            Text(
                "${hms((scrubSec ?: player.positionSec).toLong())} / ${hms(detail.audio.totalSec.toLong())}",
                modifier = Modifier.weight(1f),
                style = mono.bodySmall,
                color = palette.textMuted,
            )
            // docs/09 "Playback": speed and Skip silence, between the clock and Play.
            SpeedChip(actions.playbackSpeed, actions.skipSilence, actions.onSpeed, actions.onSkipSilence)
            // Not while this phone is recording: that microphone belongs to the recorder, and
            // not while the trip to Drive is still being decided — what this page will play is
            // not settled yet. Nothing stands in its place; the clock alone says there is
            // something here, later.
            if (
                RecordingPlaylist.canPlay(
                    recorderIdle = !detail.deviceRecording,
                    fetchDecided = detail.driveFetch != DriveFetch.DECIDING,
                    hasAudio = !detail.audio.isEmpty,
                )
            ) {
                BlueprintButton(
                    label = stringResource(
                        if (player.isPlaying || player.buffering) R.string.player_pause else R.string.player_play,
                    ),
                    onClick = {
                        if (player.isPlaying || player.buffering) {
                            player.pause()
                        } else if (
                            // The recorder as it is at the press, not as the last frame drew
                            // it: a start that lands between the two would otherwise get a tap
                            // meant for a screen that no longer offers Play.
                            RecordingPlaylist.canPlay(
                                recorderIdle = RecorderService.state.value == RecorderState.Idle,
                                fetchDecided = detail.driveFetch != DriveFetch.DECIDING,
                                hasAudio = !detail.audio.isEmpty,
                            )
                        ) {
                            // This screen's audio, at the press: the player holds nothing
                            // between one recording and the next (see `RecordingPlayer.stop`).
                            player.load(detail.audio)
                            player.play()
                        }
                    },
                    modifier = Modifier
                        .sizeIn(minWidth = PlayMinWidth, minHeight = PlayMinHeight)
                        .testTag("play-pause"),
                    tone = ButtonTone.PRIMARY,
                )
            }
        }

        // docs/03: nothing of this recording ever reached Drive, and what was here is gone — so
        // there is nowhere left to play it from. Only once the fetch has been decided against:
        // said while it is still DECIDING it would be a sentence the next moment takes back.
        detail.driveFetch == DriveFetch.IDLE -> Text(
            stringResource(R.string.player_no_audio),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textMuted,
        )
    }
    // Below the controls when some parts are here and on its own when none are: either way it is
    // what stands between the page and the whole recording.
    if (detail.driveFetch == DriveFetch.FAILED) {
        Text(
            stringResource(if (detail.folder) R.string.player_fetch_failed_folder else R.string.player_fetch_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textMuted,
        )
    }
}

/** docs/03 "Storage location": the trip back is to wherever the recording was copied — Drive, or the local folder. */
private fun fetchingLabel(folder: Boolean): Int = if (folder) R.string.player_fetching_folder else R.string.player_fetching

/** The whole page, when there is one line to say and nothing to read. */
@Composable
private fun Notice(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = blueprint.textMuted,
            textAlign = TextAlign.Center,
        )
        onRetry?.let { BlueprintButton(stringResource(R.string.action_retry), it) }
    }
}

internal fun TranscriptAvailability.message(): Int = when (this) {
    TranscriptAvailability.NOT_REQUESTED -> R.string.detail_not_requested
    TranscriptAvailability.FAILED -> R.string.detail_failed
    TranscriptAvailability.PARKED -> R.string.detail_parked
    TranscriptAvailability.UNAVAILABLE -> R.string.detail_unavailable
    TranscriptAvailability.EMPTY -> R.string.detail_transcript_empty
    else -> R.string.detail_pending
}
