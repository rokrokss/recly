package app.recly.wear.entry

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import app.recly.wear.R
import app.recly.wear.ui.MainActivity
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * docs/11 W5: "tap the tile → recording starts at once". The record node and at most one line of
 * status, which is all a tile is worth — the user swiped here to start recording, not to read.
 *
 * The node is a `launchAction`, not anything that touches the recorder: a `microphone` foreground
 * service started from a tile is a background start and the platform throws (the phone's
 * `RecTileService` has the same note). It opens [MainActivity] with the auto-start extra and lets a
 * visible activity do it, which is also what keeps the microphone indicator honest.
 */
class RecTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = scope.future("tile") {
        val recording = RecorderService.state.value != RecorderState.Idle
        // Idle with nothing left on this watch says nothing: the hollow node already does (UX
        // decisions of 2026-10-08). The complication keeps its own word for the same state.
        val status = entryStatus().takeUnless { !recording && it == getString(R.string.entry_ready) }
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // The status goes stale on its own — a recording started from the app, a transfer that
            // finished in a worker — and neither wakes the tile. A minute is honest and cheap; the
            // app also asks for an update when either changes (`RecWearApp.refreshEntryPoints`).
            .setFreshnessIntervalMillis(FRESHNESS_MILLIS)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(layout(requestParams.deviceConfiguration, recording, status)),
            )
            .build()
    }

    /** No images and no custom fonts: there is nothing to serve, but the callback is required. */
    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = scope.future("resources") {
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    private fun layout(device: DeviceParameters, recording: Boolean, status: String?): LayoutElementBuilders.LayoutElement {
        val content = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(recordNode(recording))
        status?.let {
            content.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            content.addContent(
                Text.Builder(this, it)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(if (recording) DANGER else TEXT))
                    .setMaxLines(2)
                    .build(),
            )
        }
        return PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(
                Text.Builder(this, getString(R.string.app_name))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(0xFFB0B0B0.toInt()))
                    .build(),
            )
            .setContent(content.build())
            .build()
    }

    /**
     * docs/09 "Shape": the app's own record node, not Material's pill (UX decisions of 2026-10-08) — a
     * square with a thick danger edge round a small filled square, and the whole node filled while it
     * records. The node draws no words, so its name is said to a screen reader.
     */
    private fun recordNode(recording: Boolean): LayoutElementBuilders.LayoutElement {
        val corner = ModifiersBuilders.Corner.Builder().setRadius(dp(RADIUS)).build()
        val inner = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(INNER))
            .setHeight(dp(INNER))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(if (recording) BACKGROUND else DANGER))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(2f)).build())
                            .build(),
                    )
                    .build(),
            )
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(dp(NODE))
            .setHeight(dp(NODE))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(if (recording) DANGER else BACKGROUND))
                            .setCorner(corner)
                            .build(),
                    )
                    .setBorder(ModifiersBuilders.Border.Builder().setWidth(dp(EDGE)).setColor(argb(DANGER)).build())
                    .setClickable(startClickable())
                    .setSemantics(
                        ModifiersBuilders.Semantics.Builder()
                            // While it records a tap opens the app (`MainActivity.consumeAutoStart` leaves a
                            // running recording alone), so the node is named for what it shows, not Stop.
                            .setContentDescription(getString(if (recording) R.string.recording_active else R.string.tile_start))
                            .setRole(ModifiersBuilders.SEMANTICS_ROLE_BUTTON)
                            .build(),
                    )
                    .build(),
            )
            .addContent(inner)
            .build()
    }

    /**
     * The extra rides in the launch action itself, so the activity is told why it was opened. It is
     * consumed exactly once — see `MainActivity.consumeAutoStart` — because a tile tap that starts
     * two recordings, or one that re-starts on every rotation, is worse than one that starts none.
     */
    private fun startClickable(): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId(CLICK_START)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .addKeyToExtraMapping(
                                MainActivity.EXTRA_AUTO_START,
                                ActionBuilders.booleanExtra(true),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

    private companion object {
        const val RESOURCES_VERSION = "1"

        /** The record screen's node, in the watch's dark palette (`WearBlueprint`). */
        const val NODE = 56f
        const val INNER = 18f
        const val EDGE = 3f
        const val RADIUS = 4f
        const val DANGER = 0xFFFA4D56.toInt()
        const val BACKGROUND = 0xFF0E0F12.toInt()
        const val TEXT = 0xFFF2F2F0.toInt()
        const val CLICK_START = "start"
        const val FRESHNESS_MILLIS = 60_000L
    }
}
