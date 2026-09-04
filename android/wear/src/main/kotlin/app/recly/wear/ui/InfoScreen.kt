package app.recly.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
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
 */
@Composable
fun InfoScreen(onBack: () -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { Section(R.string.info_shortcut_title, R.string.info_shortcut_body) }
            item { Section(R.string.info_battery_title, R.string.info_battery_body) }
            item { Section(R.string.info_transfer_title, R.string.info_transfer_body) }
            item {
                Spacer(Modifier.height(8.dp))
                // docs/09 "형태": square on the theme's 4dp, like every other control on the watch.
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(WearBlueprint.radius),
                    label = { Text(stringResource(R.string.info_back)) },
                )
            }
        }
    }
}

@Composable
private fun Section(title: Int, body: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(body),
            style = MaterialTheme.typography.bodySmall,
            // The token itself, as the rest of the watch reads it — not Material's role that
            // happens to be mapped to it.
            color = WearBlueprint.textMuted,
        )
    }
}
