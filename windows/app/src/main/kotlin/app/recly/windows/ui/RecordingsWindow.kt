package app.recly.windows.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.io.File
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.defaultMinSize
import java.text.NumberFormat
import java.util.Locale
import app.recly.windows.ui.theme.Motion
import app.recly.windows.ui.theme.Radius
import kotlinx.coroutines.delay
import androidx.compose.foundation.border
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
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
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
import recly.core.transcribe.TranscriptAvailability

/**
 * docs/08 "Result files": the recordings the popup lists, and what the `transcribe` step wrote for the
 * one that is picked. Reading it is [ShellModel]'s — the local copy, or Drive when this PC did not
 * run the step.
 *
 * The shape is the editor's (docs/09 screen principle 4): a list down the side, the thing itself beside it.
 */
@Composable
fun RecordingsWindow(model: ShellModel, strings: Strings) {
    // One player for the window rather than for the detail: the pane keeps a single bar and the
    // model behind it is swapped per pick, and picking another row has to stop what is playing.
    val player = remember { RecordingPlayer(logger = { model.logger }) }
    // docs/03 "Recordings from other devices": the list this window opened on is asked to catch up with the other
    // devices now rather than at the next job pass ([ShellModel.pullRemote]).
    LaunchedEffect(Unit) { model.pullRemote() }
    // Another recording picked, and — when the window closes — nothing left to look at: neither is
    // a reason to keep hearing the last one.
    LaunchedEffect(model.detail?.recordingId) { player.stop() }
    LaunchedEffect(model.detail?.recordingId, model.detail?.loading) {
        val detail = model.detail
        if (detail != null && !detail.loading) model.followDetailResults(detail.recordingId)
    }
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
    // docs/03 "Naming rules": audio and video dropped anywhere on the window are imported, one after another.
    val drop = remember(model) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = droppedFiles(event)
                model.importFiles(files)
                return files.isNotEmpty()
            }
        }
    }
    Row(
        Modifier.fillMaxSize().background(blueprint.background)
            .dragAndDropTarget(shouldStartDragAndDrop = { droppedFiles(it).isNotEmpty() }, target = drop),
    ) {
        Sidebar(model, strings, Modifier.width(SidebarWidth).fillMaxHeight())
        VerticalHairLine(Modifier.fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val detail = model.detail
            if (detail == null) {
                Placeholder(strings[Str.DETAIL_PICK])
            } else {
                Detail(
                    detail, player, { model.recording }, { model.playbackBlocked }, model::askToRename, model::reloadDetailResults,
                    waveforms = WaveformKeeping(
                        kept = { selection -> model.keptWaveform(detail.recordingId, selection) },
                        keep = { peaks -> model.keepWaveform(detail.recordingId, peaks) },
                    ),
                    strings = strings,
                )
            }
        }
    }
}

/**
 * docs/12 "Menu bar": the same ledger the tray's popup draws, and the same paging —
 * [app.recly.windows.jobs.Recents.PAGE] rows a page, with the next one read when the last loaded row
 * is scrolled onto. Lazy for that reason: a scrolling `Column` composes every row whether or not it
 * was ever on screen, and the last one would ask for the next page the moment it arrived.
 */
@Composable
private fun Sidebar(model: ShellModel, strings: Strings, modifier: Modifier) {
    LazyColumn(modifier.background(blueprint.surface)) {
        item {
            ScreenHeader(
                title = strings[Str.WINDOW_RECORDINGS],
                trailing = { BlueprintButton(strings[Str.IMPORT_AUDIO], model::chooseImport, tone = ButtonTone.QUIET, enabled = model.ready) },
            )
            HairLine()
            // The row is gone with the failed import, so the reason is said here, until the next import.
            model.importFailure?.let { reason ->
                Column(Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(strings[Str.IMPORT_FAILED], style = MaterialTheme.typography.bodyMedium, color = blueprint.danger)
                    Text(reason.text(strings), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
                }
                HairLine()
            }
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
                // docs/09 screen principle 8: an empty list says so in the middle of the space the list would
                // fill — a line, a muted line under it, and the one action a clear step below, centred.
                Column(
                    Modifier.fillParentMaxSize().padding(Space.l),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        strings[if (model.recentsLoading) Str.LIST_LOADING else Str.LEDGER_EMPTY],
                        style = MaterialTheme.typography.bodyMedium,
                        color = blueprint.text,
                        textAlign = TextAlign.Center,
                    )
                    if (!model.recentsLoading) {
                        Text(
                            strings[Str.LEDGER_EMPTY_HINT],
                            modifier = Modifier.padding(top = Space.xs),
                            style = MaterialTheme.typography.bodySmall,
                            color = blueprint.textMuted,
                            textAlign = TextAlign.Center,
                        )
                        BlueprintButton(
                            strings[Str.TRAY_START], model::start,
                            enabled = model.ready && !model.recording,
                            modifier = Modifier.padding(top = Space.l),
                        )
                    }
                }
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
        // docs/03 "Deleting in the app": deleting a recording is not one of the things opening it should
        // be able to do by accident, which is why the button is out here. And never over one that
        // is being written to or uploaded ([RecentItem.deletable]).
        controls = {
            // Waiting for the speech model: the row's one action is the download.
            if (item.jobStatus == recly.core.job.JobStatus.NEEDS_MODEL) {
                ModelDownloadChip(model, strings, item.modelLanguage)
            }
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
            StatusBadge(item.state.ledgerStatus(strings))
        }
        // docs/08 "Errors": what to do about it, and — for a key — where to do it. The popup's
        // expanded row says the same thing about the same recording ([FailureReason]).
        FailureReason(item, strings) { model.settingsOpen = true }
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
    onReload: () -> Unit,
    /** Where this recording's waveform is kept between opens ([WaveformPeaks.FILE]). */
    waveforms: WaveformKeeping,
    strings: Strings,
) {
    ScreenHeader(
        // The title alone: the recording's id is not something the user reads (docs/09 screen principle 2).
        title = detail.title.text(strings),
        // Not while the take is still being written to: the core refuses to rename a recording that
        // is still running, so offering it here would be offering nothing.
        trailing = if (detail.loading || detail.writing) {
            null
        } else {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    detail.transcript?.takeIf { detail.availability != TranscriptAvailability.EMPTY }
                        ?.let { TranscriptCopyButton(it, strings) }
                    BlueprintButton(strings[Str.DETAIL_RENAME], onRename, tone = ButtonTone.QUIET)
                }
            }
        },
    )
    // A take still being written to has nothing whole to play, and nothing to say about it either.
    if (!detail.loading && !detail.writing) {
        PlayerBar(detail, player, recording, blocked, waveforms, strings)
        HairLine()
    }
    when {
        detail.loading -> Placeholder(strings[Str.DETAIL_LOADING])
        detail.transcript == null || detail.availability == TranscriptAvailability.EMPTY -> Column(
            Modifier.fillMaxSize().padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(strings[detail.availability.message()], color = blueprint.textMuted)
            if (detail.availability == TranscriptAvailability.UNAVAILABLE) {
                BlueprintButton(strings[Str.RECENT_RETRY], onReload)
            }
        }
        else -> TranscriptReader(
            transcript = detail.transcript,
                    seekableDurationSec = detail.audio.totalSec,
            canSeek = !detail.writing && !recording() && !blocked() && !detail.audio.isEmpty && detail.driveFetch != DriveFetch.DECIDING && detail.driveFetch != DriveFetch.FETCHING,
            onSeek = { if (!recording() && !blocked()) player.seek(detail.audio, it) },
            strings = strings,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * docs/08 "Result files" · docs/09 screen principle 2: the recording itself, where this PC still has it. Its
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
    waveforms: WaveformKeeping,
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
    //
    // The waveform the core kept for it comes first: when it still stands for these parts it is
    // drawn at once and nothing is decoded — opening a recording used to decode every part of it,
    // every time. A decode that runs is kept for the next open.
    LaunchedEffect(detail.audio, blocked()) {
        player.stop()
        if (!blocked() && !detail.audio.isEmpty) {
            player.prepare(detail.audio, waveforms.kept(detail.audio), waveforms.keep)
        }
    }
    val positionSec = scrubSec ?: player.positionSec
    // Having come back from Drive, the bars grow in once where the loader stood.
    var fetched by remember(detail.recordingId) { mutableStateOf(false) }
    LaunchedEffect(detail.driveFetch) { if (detail.driveFetch == DriveFetch.FETCHING) fetched = true }
    // And having been decoded here, they grow in too — shorter, in place of the loader.
    var decoded by remember(detail.audio) { mutableStateOf(false) }
    LaunchedEffect(player.waveformDecoding) { if (player.waveformDecoding) decoded = true }
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
        if (showsWaveformLoader(detail.audio, player.waveform, player.waveformFailed)) {
            // The parts are here and their shape is not yet: the loader, never a flat line — a
            // flat line reads as a silent recording. Play and the clock below do not wait for it.
            WaveformLoader(strings[Str.PLAYER_WAVEFORM_LOADING])
        } else if (!detail.audio.isEmpty) {
            Waveform(
                audio = detail.audio,
                peaks = player.waveform,
                positionSec = positionSec,
                label = strings[Str.PLAYER_POSITION],
                onScrub = { scrubSec = it },
                onSeek = { player.seek(detail.audio, it) },
                growIn = fetched || decoded,
                growMs = if (fetched) GROW_MS else GROW_DECODED_MS,
            )
        } else if (detail.driveFetch == DriveFetch.FETCHING) {
            // While the parts are coming back from Drive the row is already there, loading, so the
            // bars arrive in place rather than the pane growing a row when they do.
            WaveformLoader()
        }
        if (player.failed) Text(strings[Str.PLAYER_ERROR], color = palette.danger)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                // docs/03 ADR-017: the button's place holds how far the trip is. No words: this shell
                // is told nothing about reduce motion, so the waveform row above always says it.
                detail.driveFetch == DriveFetch.FETCHING -> FetchProgress(
                    detail.fetchProgress,
                    strings[if (detail.folder) Str.PLAYER_FOLDER_FETCHING else Str.PLAYER_FETCHING],
                    strings,
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
                    strings[if (detail.folder) Str.PLAYER_FOLDER_FETCH_FAILED else Str.PLAYER_FETCH_FAILED],
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted,
                )
            }
        }
    }
}

/**
 * docs/09 "Motion": the waveform row while the recording comes back from Drive, or while its parts are
 * decoded into a shape, with no words — short ghost ticks where the bars will be, and a hard-edged
 * band of ten that steps across them left to right, one bar a frame at 30 fps. It does not rise and
 * fall or flow the way a playing or recording waveform does, and it leaves nothing filled behind it
 * the way a playhead does. It always runs: Windows tells a Compose Desktop app nothing about reduce
 * motion (the shared loader's note).
 *
 * [label] is what a screen reader hears for the row; the Drive fetch gives none, because the
 * progress under it already says the same thing.
 */
@Composable
private fun WaveformLoader(label: String? = null) {
    val palette = blueprint
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(LOADER_FRAME_MS)
            frame++
        }
    }
    Canvas(Modifier.fillMaxWidth().height(MinTouch).clearAndSetSemantics { label?.let { contentDescription = it } }) {
        val step = WaveformStep.toPx()
        val count = (size.width / step).toInt()
        val head = frame % (count + LOADER_BAND)
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
 * the button's own outline, exactly its size (the Play label is laid out in it unseen, with the
 * button's own padding), filling with the button's own colour, so that when it is full it is the
 * button and nothing moves. The percentage is written in it in the accent — and in the button's own
 * ink over the part already filled — so it never reads as an empty, disabled button. A screen reader
 * hears [label] and the percentage.
 */
@Composable
private fun FetchProgress(fraction: Float, label: String, strings: Strings) {
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    val shown by animateFloatAsState(fraction, tween(Motion.STANDARD_MS, easing = Motion.Standard))
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val percent = remember(fraction, strings.language) {
        NumberFormat.getPercentInstance(Locale.forLanguageTag(strings.language)).format(fraction.coerceIn(0f, 1f))
    }
    Box(
        Modifier
            // BlueprintButton's own box: the minimum touch height, and its padding around the label.
            .defaultMinSize(minHeight = MinTouch)
            .clip(shape)
            .border(palette.line, palette.accent, shape)
            .drawWithContent {
                val filled = size.width * shown
                drawRect(palette.accent, size = Size(filled, size.height))
                val text = measurer.measure(percent, style)
                val at = Offset((size.width - text.size.width) / 2, (size.height - text.size.height) / 2)
                clipRect(right = filled) { drawText(text, color = palette.onAccent, topLeft = at) }
                clipRect(left = filled) { drawText(text, color = palette.accent, topLeft = at) }
            }
            .clearAndSetSemantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            }
            .padding(horizontal = Space.s, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Unseen: only its size is wanted, which is the Play button's.
        Text(strings[Str.PLAYER_PLAY], style = style, maxLines = 1, modifier = Modifier.alpha(0f))
    }
}

/** docs/09: a tenth of the row's width at a time, slow enough to read as work and not as sound. */
private const val LOADER_BAND = 10
private const val LOADER_FRAME_MS = 33L
/** The ghost ticks' height, as a share of the row. */
private const val LOADER_TICK = 0.3f
/** The bars' rise when a recording arrives from Drive: 750 ms in all, the last bar starting at 60%. */
private const val GROW_MS = 750
/** The same rise after a decode on this PC: short, because nothing travelled for it. */
private const val GROW_DECODED_MS = 300
private const val GROW_SPREAD = 0.6f

/**
 * docs/09 screen principle 2: the recording as a shape, and the one place on this page a second of it can
 * be pointed at. The whole row takes the pointer, so a click anywhere in it is a seek as much as a
 * drag across it is — and playback is not interrupted by either, because what a scrub is for is
 * hearing another part of the same take.
 *
 * docs/09 Accessibility: and the one place on it a second can be pointed at without a pointer. The row is
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
    growIn: Boolean = false,
    growMs: Int = GROW_MS,
) {
    val palette = blueprint
    val hair = palette.line
    val totalSec = audio.totalSec
    var focused by remember { mutableStateOf(false) }
    // The bars rise out of the centre line once, left first, when a recording back from Drive has its peaks.
    val reveal = remember(audio) { Animatable(if (growIn) 0f else 1f) }
    LaunchedEffect(audio, peaks.isNotEmpty()) {
        if (peaks.isNotEmpty() && reveal.value < 1f) reveal.animateTo(1f, tween(growMs, easing = LinearEasing))
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(MinTouch)
            .testTag("waveform")
            .border(if (focused) 2.dp else 0.dp, if (focused) palette.accent else androidx.compose.ui.graphics.Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
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
        // docs/09 "Lines": straight bars of one width on one gap, no caps and no gradient. Behind the
        // playhead is the accent and ahead of it the muted colour, both at full opacity — docs/09
        // "Accessibility" asks 3:1 of a graphic, and the muted token faded out to hint at "not played
        // yet" is under 2:1 on the surface. The token promotes itself to the body colour in high contrast,
        // so there is nothing here to special-case.
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
        drawRect(
            color = palette.accent,
            topLeft = Offset(playhead.coerceIn(0f, size.width - line), 0f),
            size = Size(line, size.height),
        )
    }
}

/**
 * docs/09 screen principle 2 · "Spacing": the waveform row's own rhythm. A 2dp bar on a 1dp gap, so how many
 * bars there are is however many 3dp columns the row is wide — the shape is the recording's, and
 * the number of bars is the window's.
 */
private val WaveformBar: Dp = 2.dp
private val WaveformStep: Dp = 3.dp

/** A bin with no sound in it, so that silence is still part of the timeline. */
private val WaveformMinBar: Dp = 1.dp

/** docs/09 Accessibility: what one arrow key moves the playhead, for a scrub with no pointer. */
private const val WaveformStepSec: Double = 5.0

/**
 * Where in the recording a point of the row is. The row is the whole recording end to end, so this
 * is the one piece of arithmetic the scrub is.
 */
private fun second(x: Float, width: Int, totalSec: Double): Double =
    if (width <= 0) 0.0 else (x / width).toDouble().coerceIn(0.0, 1.0) * totalSec

private fun millis(seconds: Double): Long = (seconds * 1000).toLong()

/** The files of a drop from the file manager; nothing for text or anything else. */
@OptIn(ExperimentalComposeUiApi::class)
private fun droppedFiles(event: DragAndDropEvent): List<File> = runCatching {
    val transferable = event.awtTransferable
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return@runCatching emptyList()
    (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>().filter { it.isFile }
}.getOrDefault(emptyList())

internal fun TranscriptAvailability.message(): Str = when (this) {
    TranscriptAvailability.NOT_REQUESTED -> Str.DETAIL_NOT_REQUESTED
    TranscriptAvailability.FAILED -> Str.DETAIL_FAILED
    TranscriptAvailability.PARKED -> Str.DETAIL_PARKED
    TranscriptAvailability.UNAVAILABLE -> Str.DETAIL_UNAVAILABLE
    TranscriptAvailability.EMPTY -> Str.DETAIL_TRANSCRIPT_EMPTY
    else -> Str.DETAIL_PENDING
}

/**
 * docs/09 "Motion": the waveform row is the loader while the parts on show have no shape yet and none
 * failed to decode — never a flat line, which reads as a silent recording. A decode that failed
 * leaves the baseline, which is then the truth: there is no shape to wait for.
 */
internal fun showsWaveformLoader(audio: RecordingPlaylist.Selection, peaks: FloatArray, failed: Boolean): Boolean =
    !audio.isEmpty && peaks.isEmpty() && !failed

/**
 * The core's keeping of one recording's waveform, as the detail uses it: what was kept, when it
 * still stands for the parts on show, and where a fresh decode goes.
 */
class WaveformKeeping(
    val kept: suspend (RecordingPlaylist.Selection) -> FloatArray?,
    val keep: (FloatArray) -> Unit,
)
