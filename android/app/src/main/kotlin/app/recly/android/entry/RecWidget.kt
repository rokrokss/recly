package app.recly.android.entry

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.recly.android.R
import app.recly.android.ui.MainActivity
import app.recly.android.ui.theme.BlueprintDark
import app.recly.android.ui.theme.BlueprintLight
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.Type
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState

/**
 * docs/11 A9: the home screen widget — Start/Stop and what the recorder is doing.
 *
 * Start opens the app instead of starting the service: as far as a `microphone` foreground service is
 * concerned a widget tap is a background start, the same while-in-use rule the tile obeys. Stop
 * needs no visible activity, so it goes straight to the service — and, like the notification's own
 * stop action, it queues the job right away because there is nobody here to ask for a title.
 */
class RecWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Live while the host keeps the Glance session bound; `RecApp` pushes an update for
            // when it does not.
            val recorder by RecorderService.state.collectAsState()
            Body(recorder)
        }
    }

    /**
     * docs/09 "Tokens": the app's own palette, not the launcher's Material You — the dark value on a dark home
     * screen and the light one on a light one. The record control is the record screen's square node (the
     * iPhone widget's too), beside what the recorder is doing. The container's corner is the launcher's.
     */
    @Composable
    private fun Body(recorder: RecorderState) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(PAGE)
                .cornerRadius(16.dp)
                .padding(Space.sm),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            when (recorder) {
                RecorderState.Idle ->
                    RecordNode(recording = false, busy = false, GlanceModifier.clickable(actionStartActivity(startIntent())))

                is RecorderState.Recording ->
                    RecordNode(recording = true, busy = false, GlanceModifier.clickable(actionRunCallback<StopRecording>()))

                // Starting or stopping: the answer is a second away, and a tap would only be
                // dropped by the service.
                else -> RecordNode(recording = false, busy = true, GlanceModifier)
            }
            Spacer(GlanceModifier.width(Space.sm))
            Text(
                text = string(if (recorder == RecorderState.Idle) R.string.widget_idle else R.string.widget_recording),
                style = TextStyle(color = INK, fontSize = Type.BODY_SMALL.sp, fontWeight = FontWeight.Normal),
                maxLines = 2,
            )
        }
    }

    /**
     * docs/09 "Shape": the square record node — a [NODE_EDGE] danger edge round the surface and a small danger square,
     * or, while it records, filled with the danger colour round a square of the page. Glance has no border, so the
     * edge is the danger square the face sits in. A tap target of [NODE] = 48dp, and a name for a screen reader,
     * since it draws no words: Start recording, Stop, or "…" while the recorder is between the two.
     */
    @Composable
    private fun RecordNode(recording: Boolean, busy: Boolean, modifier: GlanceModifier) {
        val name = string(
            when {
                busy -> R.string.recording_busy
                recording -> R.string.recording_stop
                else -> R.string.recording_start_label
            },
        )
        Box(
            modifier = modifier
                .size(NODE)
                .background(DANGER)
                .cornerRadius(Radius.node)
                .semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = GlanceModifier
                    .size(NODE - NODE_EDGE * 2)
                    .background(if (recording) DANGER else SURFACE)
                    .cornerRadius(Radius.node - NODE_EDGE),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    Text(string(R.string.action_processing), style = TextStyle(color = DANGER, fontSize = Type.BODY.sp, fontFamily = FontFamily.Monospace))
                } else {
                    Box(GlanceModifier.size(NODE_MARK).background(if (recording) PAGE else DANGER).cornerRadius(Radius.badge)) {}
                }
            }
        }
    }

    /**
     * No `requestedAt`: this runs when the widget is *rendered*, not when it is tapped, and the
     * `PendingIntent` the launcher then keeps is the one built here. A stamp taken now would be the
     * last redraw — a widget that has sat on the home screen since this morning would hand
     * [MainActivity] a tap hours old and have every Start dropped (docs/11 A9). So the widget leaves
     * the extra off and the activity reads it as "now", the same moment it used to stamp itself.
     */
    @Composable
    private fun startIntent() = MainActivity.startRecording(LocalContext.current)

    @Composable
    private fun string(id: Int): String = LocalContext.current.getString(id)
}

/** The palette's copies for the widget: each a day and a night value (docs/09 "Tokens"). */
private val PAGE = ColorProvider(day = BlueprintLight.background, night = BlueprintDark.background)
private val SURFACE = ColorProvider(day = BlueprintLight.surface, night = BlueprintDark.surface)
private val INK = ColorProvider(day = BlueprintLight.text, night = BlueprintDark.text)
private val DANGER = ColorProvider(day = BlueprintLight.danger, night = BlueprintDark.danger)

/** The record node at the size a one-row widget holds — the touch minimum — with the record screen's 3dp edge. */
private val NODE = MinTouch
private val NODE_EDGE = 3.dp
private val NODE_MARK = 14.dp

class RecWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecWidget()
}

/** The one entry-point action that does not need an activity behind it. */
class StopRecording : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RecorderService.stop(context)
        RecWidget().update(context, glanceId)
    }
}
