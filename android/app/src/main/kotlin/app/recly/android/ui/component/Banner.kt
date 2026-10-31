@file:OptIn(ExperimentalLayoutApi::class)

package app.recly.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.dp
import app.recly.android.R
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint

// The list's two banners, shared: the fixable-failure line ([AlertLine]) and the one-off message ([MessageBanner]).

/**
 * One reason: what it is, how many recordings are behind it, the code (none for the Drive wait), and what
 * to press — the iPhone's `AlertLine`. On a phone the sentence has the row's whole width and the code and
 * the button sit under it, at the end (N5, UX decisions of 2026-10-11): beside it, they squeezed a
 * sentence such as the quota's to a few words a line. A wider window keeps them beside it, in at most
 * half the row, folding onto a second line there when they do not fit side by side, so a long code such
 * as `LOCAL_TRANSCRIPTION_UNAVAILABLE` never squeezes the sentence to a word per line. Either way a word
 * too long for its line breaks at a hyphen, not anywhere (N2).
 */
@Composable
internal fun AlertLine(
    code: String?,
    lines: @Composable ColumnScope.() -> Unit,
    tone: BadgeTone = BadgeTone.WARNING,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    action: @Composable () -> Unit,
) {
    val sentence = Modifier
        .then(if (onClick != null) Modifier.focusRing(blueprint).clickable(onClick = onClick) else Modifier)
        .then(
            if (description != null) {
                Modifier.semantics(mergeDescendants = true) { contentDescription = description }
            } else {
                Modifier
            },
        )
    val trailing: @Composable () -> Unit = {
        code?.let { StatusBadge(LedgerStatus(it, tone)) }
        action()
    }
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(hyphens = Hyphens.Auto)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Space.m)) {
            if (maxWidth + Space.m * 2 < PHONE_WIDTH) {
                Column(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = sentence.fillMaxWidth().padding(vertical = Space.s),
                        verticalArrangement = Arrangement.spacedBy(Space.xxs),
                        content = lines,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = Space.s),
                        horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) { trailing() }
                }
                return@BoxWithConstraints
            }
            // Half the row, or the code's own width where that is more: a code is never cut.
            val half = maxOf(maxWidth / 2, code?.let { statusColumnWidth(listOf(it)) } ?: 0.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = sentence.weight(1f).padding(vertical = Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.xxs),
                    content = lines,
                )
                FlowRow(
                    modifier = Modifier.widthIn(max = half).padding(vertical = Space.s),
                    horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) { trailing() }
            }
        }
    }
}

/** A window narrower than this is a phone's (the compact width class): the banner stacks its lines. */
private val PHONE_WIDTH = 600.dp

/**
 * What the last delete or retry had to say, as the iPhone's banner: the sentence on the surface inside an edge of its tone's
 * colour — the warning's, or the failure's when it says something failed ([failed]). A tap puts it away.
 */
@Composable
internal fun MessageBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, failed: Boolean = false) {
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouch)
            .focusRing(palette, Radius.node)
            .background(palette.surface, shape)
            .border(palette.line, if (failed) palette.danger else palette.warning, shape)
            .clickable(onClickLabel = stringResource(R.string.action_close), onClick = onDismiss)
            .padding(horizontal = Space.sm, vertical = Space.s)
            .testTag("message"),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 14, as the iPhone's, the Mac's and Windows' banner: a sentence to read, not a row's secondary line (2026-10-28).
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (failed) palette.danger else palette.warningInk)
    }
}
