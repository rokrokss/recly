package app.recly.wear.entry

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import app.recly.wear.R
import app.recly.wear.ui.MainActivity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * docs/11 W5: "타일 탭 → 즉시 녹음". One chip and one line of status, which is all a tile is worth —
 * the user swiped here to start recording, not to read.
 *
 * The chip is a `launchAction`, not anything that touches the recorder: a `microphone` foreground
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
        val status = entryStatus()
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // The status goes stale on its own — a recording started from the app, a transfer that
            // finished in a worker — and neither wakes the tile. A minute is honest and cheap; the
            // app also asks for an update when either changes (`RecWearApp.refreshEntryPoints`).
            .setFreshnessIntervalMillis(FRESHNESS_MILLIS)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(layout(requestParams.deviceConfiguration, status)),
            )
            .build()
    }

    /** No images and no custom fonts: there is nothing to serve, but the callback is required. */
    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = scope.future("resources") {
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    private fun layout(device: DeviceParameters, status: String): LayoutElementBuilders.LayoutElement =
        PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(
                Text.Builder(this, getString(R.string.app_name))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(androidx.wear.protolayout.ColorBuilders.argb(0xFFB0B0B0.toInt()))
                    .build(),
            )
            .setContent(
                Text.Builder(this, status)
                    .setTypography(Typography.TYPOGRAPHY_BODY1)
                    .setColor(androidx.wear.protolayout.ColorBuilders.argb(0xFFFFFFFF.toInt()))
                    .setMaxLines(2)
                    .build(),
            )
            .setPrimaryChipContent(
                CompactChip.Builder(this, getString(R.string.tile_start), startClickable(), device).build(),
            )
            .build()

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
        const val CLICK_START = "start"
        const val FRESHNESS_MILLIS = 60_000L
    }
}
