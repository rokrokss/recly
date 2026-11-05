package app.recly.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
 * One reason: what it is, how many recordings are behind it, and what to press — the iPhone's `AlertLine`.
 * On a phone the sentence has the row's whole width and the button sits under it, at the end (N5, UX
 * decisions of 2026-10-11): beside it, it squeezed a sentence such as the quota's to a few words a line. A
 * wider window keeps it beside it, in at most half the row. Either way a word too long for its line breaks
 * at a hyphen, not anywhere (N2).
 *
 * No badge (2026-11-05, user decision): beside the button, at its size, `Failed` read as a second button that
 * did nothing, and it only said again what the line's colour and the row's own badge say.
 */
@Composable
internal fun AlertLine(
    lines: @Composable ColumnScope.() -> Unit,
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
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(hyphens = Hyphens.Auto)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Space.m)) {
            if (maxWidth + Space.m * 2 < PHONE_WIDTH) {
                Column(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = sentence.fillMaxWidth().padding(vertical = Space.s),
                        verticalArrangement = Arrangement.spacedBy(Space.xxs),
                        content = lines,
                    )
                    Box(Modifier.fillMaxWidth().padding(bottom = Space.s), contentAlignment = Alignment.CenterEnd) { action() }
                }
                return@BoxWithConstraints
            }
            val half = maxWidth / 2
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
                Box(Modifier.widthIn(max = half).padding(vertical = Space.s)) { action() }
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
