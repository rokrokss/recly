package app.recly.windows.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.IconButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import kotlinx.coroutines.delay
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptDocument
import recly.core.transcribe.TranscriptNormalizer
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings

/** Cached paragraphs remain selectable and seekable while header actions stay visible. */
@Composable
internal fun TranscriptReader(
    transcript: Transcript,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
    seekableDurationSec: Double = Double.POSITIVE_INFINITY,
    strings: Strings,
) {
    val document = remember(transcript) { TranscriptDocument(transcript) }
    val list = rememberLazyListState()
    LaunchedEffect(transcript.recordingId) { list.scrollToItem(0) }
    SelectionContainer(modifier) {
        LazyColumn(Modifier.fillMaxSize().testTag("transcript-passages"), state = list,
            contentPadding = PaddingValues(vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            items(document.blocks, key = { it.index }) { block ->
                Column(Modifier.padding(horizontal = Space.m)) {
                    val stamp = LedgerFormat.elapsed((block.start * 1000).toLong())
                    val seekLabel = strings[Str.TRANSCRIPT_SEEK, stamp]
                    BlueprintButton("$stamp ${block.speaker}", { onSeek(block.start) }, enabled = canSeek && block.start < seekableDurationSec,
                        modifier = Modifier.testTag("transcript-time-${block.index}")
                            .semantics { contentDescription = seekLabel }, tone = ButtonTone.QUIET, monospace = true)
                    Text(block.text, style = MaterialTheme.typography.bodyMedium, color = blueprint.text,
                        modifier = Modifier.testTag("transcript-text-${block.index}"))
                }
            }
        }
    }
}

/** The header's copy action; feedback stays icon-only and retains a localized spoken label. */
@Composable
internal fun TranscriptCopyButton(transcript: Transcript, strings: Strings) {
    var copied by remember(transcript.recordingId) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val label = strings[if (copied) Str.TRANSCRIPT_COPIED else Str.TRANSCRIPT_COPY]
    val ink = blueprint.textMuted
    LaunchedEffect(copied) { if (copied) { delay(3000); copied = false } }
    IconButton(onClick = {
        clipboard.setText(AnnotatedString(TranscriptNormalizer.text(transcript)))
        copied = true
    }, modifier = Modifier.testTag("transcript-copy").semantics { contentDescription = label }) {
        Canvas(Modifier.size(24.dp).clearAndSetSemantics {}) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                if (copied) {
                    drawPath(Path().apply { moveTo(4f, 12f); lineTo(9f, 17f); lineTo(20f, 6f) },
                        ink, style = Stroke(2f))
                } else {
                    drawPath(Path().apply { moveTo(15f, 2f); lineTo(3f, 2f); lineTo(3f, 17f) },
                        ink, style = Stroke(1.5f))
                    drawRect(ink, topLeft = Offset(7f, 6f), size = Size(14f, 16f), style = Stroke(1.5f))
                }
            }
        }
    }
}
