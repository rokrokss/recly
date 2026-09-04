package app.recly.android.entry

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.recly.android.R
import app.recly.android.ui.MainActivity
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
            GlanceTheme {
                // Live while the host keeps the Glance session bound; `RecApp` pushes an update for
                // when it does not.
                val recorder by RecorderService.state.collectAsState()
                Body(recorder)
            }
        }
    }

    @Composable
    private fun Body(recorder: RecorderState) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Text(
                text = string(if (recorder == RecorderState.Idle) R.string.widget_idle else R.string.widget_recording),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium),
            )
            Spacer(GlanceModifier.height(8.dp))
            when (recorder) {
                RecorderState.Idle ->
                    Pill(R.string.recording_start, GlanceModifier.clickable(actionStartActivity(startIntent())))

                is RecorderState.Recording ->
                    Pill(R.string.recording_stop, GlanceModifier.clickable(actionRunCallback<StopRecording>()))

                // Starting or stopping: the answer is a second away, and a tap would only be
                // dropped by the service.
                else -> Pill(R.string.recording_busy, GlanceModifier)
            }
        }
    }

    /** Glance's `Button` renders as a platform button the launcher restyles; a pill is predictable. */
    @Composable
    private fun Pill(label: Int, modifier: GlanceModifier) {
        Text(
            text = string(label),
            style = TextStyle(color = GlanceTheme.colors.onPrimary, fontWeight = FontWeight.Bold),
            modifier = modifier
                .background(GlanceTheme.colors.primary)
                .cornerRadius(20.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
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
