package app.recly.wear.ui

import android.icu.text.BreakIterator
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import app.recly.wear.R
import app.recly.wear.ui.theme.WearBlueprint

/**
 * docs/11 W5 "설정 안내 화면", and text is deliberately all it is. Both things the user has to do
 * live in apps this one cannot deep-link into: the double-press mapping is in Samsung's own
 * settings (the intent is undocumented and vendor-specific) and the battery exemption is in Galaxy
 * Wearable, on the *phone*. A button that silently did nothing on a non-Samsung watch would be
 * worse than a sentence that says where to go.
 *
 * The second one is not a nicety: docs/11 "주의" — Galaxy Wearable under battery optimisation loses
 * the Bluetooth proxy, and a watch whose proxy is gone holds every recording it makes.
 *
 * docs/11 "주의" (round screens): the list shrinks an item only once most of it has left the
 * screen, so a whole paragraph as one item ran off the curve above and below the middle — Play
 * rejected the build for it. Every title and every sentence is its own item, centred in a column
 * narrower than the scaffold's, and the screen opens on the first of them.
 */
@Composable
fun InfoScreen(onBack: () -> Unit) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val inset = textInset()
    val sections = listOf(
        stringResource(R.string.info_shortcut_title) to sentences(R.string.info_shortcut_body),
        stringResource(R.string.info_battery_title) to sentences(R.string.info_battery_body),
        stringResource(R.string.info_transfer_title) to sentences(R.string.info_transfer_body),
    )
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            sections.forEach { (title, body) -> section(title, body, inset) }
            item {
                Spacer(Modifier.height(8.dp))
                // docs/09 "형태": square on the theme's 4dp, like every other control on the watch.
                Button(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = inset),
                    shape = RoundedCornerShape(WearBlueprint.radius),
                    label = { Text(stringResource(R.string.info_back)) },
                )
            }
        }
    }
}

private fun ScalingLazyListScope.section(title: String, body: List<String>, inset: Dp) {
    item {
        Text(
            text = title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = inset, end = inset, top = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
    }
    items(body) { sentence ->
        Text(
            text = sentence,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = inset),
            style = MaterialTheme.typography.bodySmall,
            // The token itself, as the rest of the watch reads it — not Material's role that
            // happens to be mapped to it.
            color = WearBlueprint.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/** The body split where the language ends a sentence — ". ", "。", "।" — so translations stay prose. */
@Composable
private fun sentences(body: Int): List<String> {
    val text = stringResource(body)
    val locale = LocalConfiguration.current.locales[0]
    return remember(text, locale) {
        val breaks = BreakIterator.getSentenceInstance(locale).apply { setText(text) }
        buildList {
            var start = breaks.first()
            var end = breaks.next()
            while (end != BreakIterator.DONE) {
                add(text.substring(start, end).trim())
                start = end
                end = breaks.next()
            }
        }
    }
}

/**
 * On top of the scaffold's 5.2%, enough to bring text to the square inscribed in a round screen
 * (14.6% a side) — as a share of the width, because a fixed dp is a different margin on every watch.
 */
@Composable
private fun textInset(): Dp = (LocalConfiguration.current.screenWidthDp * 0.094f).dp
