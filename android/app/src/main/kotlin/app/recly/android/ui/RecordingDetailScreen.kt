package app.recly.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.BlueprintDialogText
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.DialogTone
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.ScreenHeader
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import kotlinx.coroutines.delay
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptSegment

/**
 * docs/08 "결과 파일", deliverable 3: what the transcribe step wrote, as the speaker turns it is made
 * of. Reading it is [JobsViewModel]'s: this draws what came back and knows nothing about where it
 * came from.
 *
 * It is a page behind a ledger row rather than a tab of its own (docs/09 화면 원칙 2), so the header
 * carries the way back.
 */
@Composable
fun RecordingDetailScreen(
    detail: DetailState,
    onClose: () -> Unit,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One player per recording: opening another one releases the one this was playing, and so does
    // leaving the page. Nothing keeps playing behind a screen nobody is looking at.
    val context = LocalContext.current
    val player = remember(detail.recordingId) { RecordingPlayer(context) }
    DisposableEffect(player) { onDispose { player.release() } }
    // The clock is wound from here, and only while something is playing (docs/09 "모션": nothing
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

    // docs/03 "제목": whether the dialog that renames this recording is up. Keyed on the recording,
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

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = detail.title ?: stringResource(R.string.jobs_untitled),
            meta = detail.recordingId,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    // Not while the recorder is still writing into this take: the core refuses to
                    // rename one, and an action that does nothing is not one to offer.
                    if (!detail.writing) {
                        BlueprintButton(
                            label = stringResource(R.string.detail_rename),
                            onClick = { renaming = true },
                            modifier = Modifier.testTag("detail-rename"),
                            tone = ButtonTone.QUIET,
                        )
                    }
                    BlueprintButton(
                        label = stringResource(R.string.action_close),
                        onClick = onClose,
                        modifier = Modifier.testTag("detail-close"),
                        tone = ButtonTone.QUIET,
                    )
                }
            },
        )
        HairLine()

        // A take still being written to has nothing whole to play yet, and nothing to say about it.
        if (!detail.loading && !detail.writing) {
            PlayerBar(detail, player)
            HairLine()
        }

        if (detail.loading) {
            Notice(stringResource(R.string.detail_loading))
            return@Column
        }
        if (detail.transcript == null) {
            Notice(stringResource(R.string.detail_empty))
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.m, vertical = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            TranscriptText(detail.transcript)
        }
    }
}

/**
 * docs/03 "제목": the recording's name, asked again. The same question the end of a recording asks
 * (`RecordingScreen`'s `TitleDialog`) without the second half of it — how many people were in the
 * room is a hint the transcribe step has long since used by the time this page exists.
 *
 * Empty is an answer: it clears the title back to the timestamp name (docs/09 화면 원칙 5 — title,
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
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        )
        BlueprintDialogText(
            stringResource(R.string.recording_title_hint),
            tone = DialogTone.MUTED,
        )
    }
}

/**
 * docs/08 "결과 파일" · docs/09 화면 원칙 2: the recording itself, where this phone still has it. Its
 * shape on top, with the playhead moving across it and a drag on it to move where the playhead is,
 * and the button and the recording's own clock under that. RecKit's detail and the Windows shell's
 * draw the same bar, in the same words.
 */
@Composable
private fun PlayerBar(detail: DetailState, player: RecordingPlayer) {
    val palette = blueprint
    // Where the finger is while it is on the waveform, and null the rest of the time. The playhead
    // and the clock follow it rather than the player: the seek happens when the finger lets go, and
    // a bar that only moved then would not be a scrub.
    var scrubSec by remember(detail.audio) { mutableStateOf<Double?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        // Whenever there is something to draw, and not only when it can be played: Play is what a
        // recording in progress or an undecided fetch gates, while a scrub before either is settled
        // is no more than where the next press will start.
        if (!detail.audio.isEmpty) {
            Waveform(
                audio = detail.audio,
                peaks = detail.waveform,
                positionSec = scrubSec ?: player.positionSec,
                onScrub = { scrubSec = it },
                onSeek = { player.seek(detail.audio, it) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerControls(detail, player, scrubSec)
        }
    }
}

/**
 * docs/09 화면 원칙 2: the recording as a shape, and the one place on this page a second of it can
 * be pointed at. The drag is on the whole row, so a tap anywhere in it is a seek — and playback is
 * not interrupted by either, because what a scrub is for is hearing another part of the same take.
 *
 * @param peaks the recording's own timeline, or empty until the decode is through — the row keeps
 *   its height and its playhead either way, so the bar does not change shape when the peaks arrive.
 * @param onScrub where the finger is, while it is down, and null when it lets go.
 */
@Composable
private fun Waveform(
    audio: RecordingPlaylist.Selection,
    peaks: FloatArray,
    positionSec: Double,
    onScrub: (Double?) -> Unit,
    onSeek: (Double) -> Unit,
) {
    val palette = blueprint
    val hair = palette.line
    val totalSec = audio.totalSec
    // docs/09 접근성: the row reports itself as the recording's position, and a reader that cannot
    // see the shape moves the playhead by setting it.
    val label = stringResource(R.string.player_position)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(MinTouch)
            .testTag("waveform")
            .semantics {
                contentDescription = label
                progressBarRangeInfo =
                    ProgressBarRangeInfo(positionSec.toFloat(), 0f..totalSec.toFloat().coerceAtLeast(0f))
                setProgress { target ->
                    onSeek(target.toDouble())
                    true
                }
            }
            // Keyed on the recording and not on its seconds: what a release seeks is the selection
            // this gesture was composed with, and two recordings can be the same length.
            .pointerInput(audio) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var sec = second(down.position.x, size.width, totalSec)
                    onScrub(sec)
                    down.consume()
                    var pressed = true
                    while (pressed) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        sec = second(change.position.x, size.width, totalSec)
                        onScrub(sec)
                        change.consume()
                        pressed = change.pressed
                    }
                    onSeek(sec)
                    onScrub(null)
                }
            },
    ) {
        val playhead = if (totalSec > 0) (size.width * positionSec / totalSec).toFloat() else 0f
        val step = WaveformStep.toPx()
        val line = hair.toPx()
        val bins = RecordingWaveform.bins(peaks, (size.width / step).toInt())
        // docs/09 "선": straight bars of one width on one gap, no caps and no gradient. Behind the
        // playhead is the accent and ahead of it the muted colour, both at full opacity — docs/09
        // 접근성 asks 3:1 of a graphic, and the muted token faded out to hint at "not played yet" is
        // under 2:1 on the surface.
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
        bins.forEachIndexed { index, bin ->
            val x = index * step
            // Silence is a tick rather than nothing, so the row reads as the whole recording.
            val height = maxOf(WaveformMinBar.toPx(), bin * size.height)
            drawRect(
                color = if (x <= playhead) palette.accent else palette.textMuted,
                topLeft = Offset(x, (size.height - height) / 2),
                size = Size(WaveformBar.toPx(), height),
            )
        }
        drawRect(
            color = palette.accent,
            topLeft = Offset(playhead.coerceIn(0f, size.width - line), 0f),
            size = Size(line, size.height),
        )
    }
}

/**
 * docs/09 화면 원칙 2 · "간격": the waveform row's own rhythm. A 2dp bar on a 1dp gap, so how many
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
 * docs/08 "결과 파일": one button and the recording's own clock, or the one sentence there is to say
 * instead of them.
 */
@Composable
private fun PlayerControls(detail: DetailState, player: RecordingPlayer, scrubSec: Double?) {
    val palette = blueprint
    when {
        // docs/03 ADR-017: where the clock is, because it is what the clock is instead of. No
        // Play either — there is nothing whole to play until the parts are back.
        detail.driveFetch == DriveFetch.FETCHING -> Text(
            stringResource(R.string.player_fetching),
            style = mono.bodySmall,
            color = palette.textMuted,
        )

        !detail.audio.isEmpty -> {
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
                        if (player.isPlaying) R.string.player_pause else R.string.player_play,
                    ),
                    onClick = {
                        if (player.isPlaying) {
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
                    modifier = Modifier.testTag("play-pause"),
                    tone = ButtonTone.PRIMARY,
                )
            }
            // docs/07 rule 4: a clock is a stamp, not a sentence. The finger while there is one on
            // the waveform, and the player the rest of the time — the two are the same playhead.
            Text(
                "${hms((scrubSec ?: player.positionSec).toLong())} / ${hms(detail.audio.totalSec.toLong())}",
                style = mono.bodySmall,
                color = palette.textMuted,
            )
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
    // Beside the clock when some parts are here and on its own when none are: either way it is
    // what stands between the page and the whole recording.
    if (detail.driveFetch == DriveFetch.FAILED) {
        Text(
            stringResource(R.string.player_fetch_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textMuted,
        )
    }
}

/** The whole page, when there is one line to say and nothing to read. */
@Composable
private fun Notice(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Space.l),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = blueprint.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/** docs/08 `transcript.json`: one block per speaker turn, stamped on the recording's own clock. */
@Composable
private fun TranscriptText(transcript: Transcript) {
    val palette = blueprint
    turns(transcript.segments).forEach { turn ->
        Column(modifier = Modifier.fillMaxWidth()) {
            // The stamp and the speaker are codes, not sentences (docs/07 rule 4).
            Text("${hms(turn.start.toLong())} ${turn.speaker}", style = mono.small, color = palette.textMuted)
            Text(turn.text, style = MaterialTheme.typography.bodyMedium, color = palette.text)
        }
    }
}

private data class Turn(val speaker: String, val start: Double, val text: String)

/**
 * Consecutive segments of one speaker read as one thing said, which is how the `.txt` the step
 * writes is built too (`TranscriptNormalizer.text`).
 */
private fun turns(segments: List<TranscriptSegment>): List<Turn> {
    val turns = mutableListOf<Turn>()
    segments.forEach { segment ->
        val last = turns.lastOrNull()
        if (last != null && last.speaker == segment.speaker) {
            turns[turns.lastIndex] = last.copy(text = last.text + " " + segment.text.trim())
        } else {
            turns += Turn(segment.speaker, segment.start, segment.text.trim())
        }
    }
    return turns
}
