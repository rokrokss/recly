package app.recly.windows.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.text
import app.recly.windows.jobs.RecentItem
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.HairLine
import app.recly.windows.ui.component.Placeholder
import app.recly.windows.ui.component.ScreenHeader
import app.recly.windows.ui.component.SidebarRow
import app.recly.windows.ui.component.SidebarWidth
import app.recly.windows.ui.component.StatusBadge
import app.recly.windows.ui.component.VerticalHairLine
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptSegment

/**
 * docs/08 "결과 파일": the recordings the popup lists, and what the `transcribe` step wrote for the
 * one that is picked. Reading it is [ShellModel]'s — the local copy, or Drive when this PC did not
 * run the step.
 *
 * The shape is the editor's (docs/09 화면 원칙 4): a list down the side, the thing itself beside it.
 */
@Composable
fun RecordingsWindow(model: ShellModel, strings: Strings) {
    // One player for the window rather than for the detail: the pane keeps a single bar and the
    // model behind it is swapped per pick, and picking another row has to stop what is playing.
    val player = remember { RecordingPlayer(logger = { model.logger }) }
    // docs/03 "다른 기기의 녹음": the list this window opened on is asked to catch up with the other
    // devices now rather than at the next job pass ([ShellModel.pullRemote]).
    LaunchedEffect(Unit) { model.pullRemote() }
    // Another recording picked, and — when the window closes — nothing left to look at: neither is
    // a reason to keep hearing the last one.
    LaunchedEffect(model.detail?.recordingId) { player.stop() }
    // docs/03 ADR-006: a desktop capture takes the system audio with it, so playback left running
    // under one would be *in* the recording — and a delete removes the very file it is reading.
    // Taking Play off the bar is not enough — a recording can be started from the tray while this
    // window is up — so what is playing is stopped here. On the gate rather than on `recording`,
    // which is only the capture's half of it and arrives only once the capture is up ([PlaybackGate]).
    LaunchedEffect(model.playbackBlocked) { if (model.playbackBlocked) player.stop() }
    // Deleting the recording is the model's, and the ffmpeg holding its file open is this player's:
    // see [ShellModel.usePlayer].
    DisposableEffect(player) {
        model.usePlayer(player)
        onDispose {
            model.usePlayer(null)
            player.stop()
        }
    }
    Row(Modifier.fillMaxSize().background(blueprint.background)) {
        Sidebar(model, strings, Modifier.width(SidebarWidth).fillMaxHeight())
        VerticalHairLine(Modifier.fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val detail = model.detail
            if (detail == null) {
                Placeholder(strings[Str.DETAIL_PICK])
            } else {
                Detail(detail, player, { model.recording }, { model.playbackBlocked }, model::askToRename, strings)
            }
        }
    }
}

/**
 * docs/12 "메뉴바": the same ledger the tray's popup draws, and the same paging —
 * [app.recly.windows.jobs.Recents.PAGE] rows a page, with the next one read when the last loaded row
 * is scrolled onto. Lazy for that reason: a scrolling `Column` composes every row whether or not it
 * was ever on screen, and the last one would ask for the next page the moment it arrived.
 */
@Composable
private fun Sidebar(model: ShellModel, strings: Strings, modifier: Modifier) {
    LazyColumn(modifier.background(blueprint.surface)) {
        item {
            ScreenHeader(title = strings[Str.WINDOW_RECORDINGS])
            HairLine()
        }
        items(model.recents, key = { it.id }) { item ->
            // The last loaded row is on screen, so the page after it is asked for.
            if (item.id == model.recents.last().id) {
                LaunchedEffect(item.id) { model.loadMoreRecents() }
            }
            RecordingRow(model, item, strings, selected = model.detail?.recordingId == item.id)
        }
        if (model.recents.isEmpty()) {
            item {
                Text(
                    strings[Str.LEDGER_EMPTY],
                    modifier = Modifier.padding(Space.l),
                    style = MaterialTheme.typography.bodySmall,
                    color = blueprint.textMuted,
                )
            }
        }
    }
}

@Composable
private fun RecordingRow(model: ShellModel, item: RecentItem, strings: Strings, selected: Boolean) {
    val palette = blueprint
    SidebarRow(
        title = item.title.text(strings),
        selected = selected,
        onOpen = { model.openDetail(item) },
        // docs/03 "앱에서 지우기": deleting a recording is not one of the things opening it should
        // be able to do by accident, which is why the button is out here. And never over one that
        // is being written to or uploaded ([RecentItem.deletable]).
        controls = {
            Box(Modifier.weight(1f))
            if (item.deletable) {
                BlueprintButton(
                    label = strings[Str.DELETE],
                    onClick = { model.askToDelete(item) },
                    tone = ButtonTone.DANGER,
                )
            }
        },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${LedgerFormat.date(item.startedAt)} ${LedgerFormat.time(item.startedAt)}",
                style = mono.small,
                color = palette.textMuted,
            )
            StatusBadge(item.state.ledgerStatus())
        }
        // docs/08 "오류": what to do about it, and — for a key — where to do it. The popup's
        // expanded row says the same thing about the same recording ([FailureReason]).
        FailureReason(item, strings) { model.editWorkflowOf(item) }
    }
}

@Composable
private fun Detail(
    detail: RecordingDetail,
    player: RecordingPlayer,
    /**
     * Whether this PC is recording: what the speaker may do while the microphone is taken. Asked
     * rather than handed over, because the press asks it again — see [PlayerBar].
     */
    recording: () -> Boolean,
    /**
     * Whether anything else is in the way — a capture opening, a delete or a disconnect removing
     * the files. Asked at the press for the same reason [recording] is ([PlaybackGate]).
     */
    blocked: () -> Boolean,
    /** docs/03: the name is the one thing on this page the user can change, so it is changed here. */
    onRename: () -> Unit,
    strings: Strings,
) {
    ScreenHeader(
        title = detail.title.text(strings),
        meta = detail.recordingId,
        // Not while the take is still being written to: the core refuses to rename a recording that
        // is still running, so offering it here would be offering nothing.
        trailing = if (detail.loading || detail.writing) {
            null
        } else {
            { BlueprintButton(strings[Str.DETAIL_RENAME], onRename, tone = ButtonTone.QUIET) }
        },
    )
    // A take still being written to has nothing whole to play, and nothing to say about it either.
    if (!detail.loading && !detail.writing) {
        PlayerBar(detail, player, recording, blocked, strings)
        HairLine()
    }
    when {
        detail.loading -> Placeholder(strings[Str.DETAIL_LOADING])
        detail.transcript == null -> Placeholder(strings[Str.DETAIL_EMPTY])
        else -> Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.m, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TranscriptText(detail.transcript)
        }
    }
}

/**
 * docs/08 "결과 파일" · docs/09 화면 원칙 2: the recording itself, where this PC still has it. Its
 * shape on top, with the playhead moving across it and a drag on it to move where the playhead is,
 * and the button and the recording's own clock under that. RecKit's
 * `RecordingDetailView.playerBar`, in the same words.
 */
@Composable
private fun PlayerBar(
    detail: RecordingDetail,
    player: RecordingPlayer,
    recording: () -> Boolean,
    blocked: () -> Boolean,
    strings: Strings,
) {
    val palette = blueprint
    // Where the pointer is while it is on the waveform, and null the rest of the time. The playhead
    // and the clock follow it rather than the player: the seek happens when the drag ends, and a
    // bar that only moved then would not be a scrub.
    var scrubSec by remember(detail.audio) { mutableStateOf<Double?>(null) }
    // The shape is decoded for whatever the bar is showing, and only once per recording. The gate
    // is a key and not only a guard: a decode started while a delete was running would be an ffmpeg
    // on a part the core is about to remove, and one that [RecordingPlayer.stop] — which the gate's
    // own effect above ran before the delete — had already been past. So none starts while it is
    // up, and the effect runs again on the way down, when there is something left to draw.
    LaunchedEffect(detail.audio, blocked()) { if (!blocked()) player.prepare(detail.audio) }
    val positionSec = scrubSec ?: player.positionSec
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
                peaks = player.waveform,
                positionSec = positionSec,
                label = strings[Str.PLAYER_POSITION],
                onScrub = { scrubSec = it },
                onSeek = { player.seek(detail.audio, it) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                // docs/03 ADR-017: where the clock is, because it is what the clock is instead of. No
                // Play either — there is nothing whole to play until the parts are back.
                detail.driveFetch == DriveFetch.FETCHING -> Text(
                    strings[Str.PLAYER_FETCHING],
                    style = mono.small,
                    color = palette.textMuted,
                )

                !detail.audio.isEmpty -> {
                    // Not while this PC is recording: the microphone and the speaker are one session on
                    // the phone (RecKit's `RecordingPlayer`), and this shell says the same thing so the
                    // page does not offer here what it refuses there. Nor while the trip to Drive is
                    // still being decided: what Play would start is not settled yet. The clock stays
                    // either way, so the bar does not change shape when the button appears.
                    val fetchDecided = detail.driveFetch != DriveFetch.DECIDING
                    if (
                        RecordingPlaylist.canPlay(
                            !recording(),
                            fetchDecided,
                            !detail.audio.isEmpty,
                            blocked(),
                        )
                    ) {
                        BlueprintButton(
                            label = if (player.playing) strings[Str.PLAYER_PAUSE] else strings[Str.PLAYER_PLAY],
                            onClick = {
                                if (player.playing) {
                                    player.pause()
                                } else if (
                                    // The recorder and the gate as they are at the press, not as
                                    // the last frame drew them: a start or a delete that lands
                                    // between the two would otherwise get a press meant for a bar
                                    // that no longer offers Play.
                                    RecordingPlaylist.canPlay(
                                        !recording(),
                                        fetchDecided,
                                        !detail.audio.isEmpty,
                                        blocked(),
                                    )
                                ) {
                                    player.play(detail.audio)
                                }
                            },
                            tone = ButtonTone.PRIMARY,
                        )
                    }
                    // docs/07 rule 4: a clock is a stamp, not a sentence.
                    Text(
                        "${LedgerFormat.elapsed(millis(positionSec))} / ${LedgerFormat.elapsed(millis(detail.audio.totalSec))}",
                        style = mono.small,
                        color = palette.textMuted,
                    )
                }

                // docs/03: nothing of this recording ever reached Drive, and what was here is gone — so
                // there is nowhere left to play it from. Only once the fetch has been decided against:
                // said while it is still DECIDING it would be a sentence the next moment takes back.
                detail.driveFetch == DriveFetch.IDLE -> Text(
                    strings[Str.PLAYER_NO_AUDIO],
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted,
                )
            }
            // Beside the clock when some parts are here and on its own when none are: either way it is
            // what stands between the page and the whole recording.
            if (detail.driveFetch == DriveFetch.FAILED) {
                Text(
                    strings[Str.PLAYER_FETCH_FAILED],
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted,
                )
            }
        }
    }
}

/**
 * docs/09 화면 원칙 2: the recording as a shape, and the one place on this page a second of it can
 * be pointed at. The whole row takes the pointer, so a click anywhere in it is a seek as much as a
 * drag across it is — and playback is not interrupted by either, because what a scrub is for is
 * hearing another part of the same take.
 *
 * docs/09 접근성: and the one place on it a second can be pointed at without a pointer. The row is
 * a focus stop that reports itself as the recording's position — the reading a screen reader gives
 * is the stamp the clock beside it shows, because that is what the playhead is — and the arrow keys
 * move it by [WaveformStepSec], which is the adjustable action RecKit's own bar has.
 *
 * @param peaks the recording's own timeline, or empty until [RecordingPlayer.prepare]'s decode is
 *   through — the row keeps its height and its playhead either way, so the bar does not change
 *   shape when the peaks arrive.
 * @param label what the row is, for a reader that cannot see the shape.
 * @param onScrub where the pointer is, while it is down, and null when it lets go.
 */
@Composable
private fun Waveform(
    audio: RecordingPlaylist.Selection,
    peaks: FloatArray,
    positionSec: Double,
    label: String,
    onScrub: (Double?) -> Unit,
    onSeek: (Double) -> Unit,
) {
    val palette = blueprint
    val hair = palette.line
    val totalSec = audio.totalSec
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
            // Before [focusable], so the row's own node sees the key before the focus system takes
            // the arrows for moving between stops.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> onSeek(positionSec - WaveformStepSec)
                    Key.DirectionRight -> onSeek(positionSec + WaveformStepSec)
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
        // under 2:1 on the surface. The token promotes itself to the body colour in high contrast,
        // so there is nothing here to special-case.
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
 * the number of bars is the window's.
 */
private val WaveformBar: Dp = 2.dp
private val WaveformStep: Dp = 3.dp

/** A bin with no sound in it, so that silence is still part of the timeline. */
private val WaveformMinBar: Dp = 1.dp

/** docs/09 접근성: what one arrow key moves the playhead, for a scrub with no pointer. */
private const val WaveformStepSec: Double = 5.0

/**
 * Where in the recording a point of the row is. The row is the whole recording end to end, so this
 * is the one piece of arithmetic the scrub is.
 */
private fun second(x: Float, width: Int, totalSec: Double): Double =
    if (width <= 0) 0.0 else (x / width).toDouble().coerceIn(0.0, 1.0) * totalSec

private fun millis(seconds: Double): Long = (seconds * 1000).toLong()

/** docs/08 `transcript.json`: one block per speaker turn, stamped on the recording's own clock. */
@Composable
private fun TranscriptText(transcript: Transcript) {
    val palette = blueprint
    turns(transcript.segments).forEach { turn ->
        Text(
            "${LedgerFormat.elapsed((turn.start * 1000).toLong())} ${turn.speaker}",
            style = mono.small,
            color = palette.textMuted,
            modifier = Modifier.padding(top = Space.s),
        )
        Text(turn.text, style = MaterialTheme.typography.bodyMedium, color = palette.text)
    }
}

private data class Turn(val speaker: String, val start: Double, val text: String)

/** Consecutive segments of one speaker read as one thing said, as `TranscriptNormalizer.text` does. */
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

