package app.recly.wear.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * docs/11 W6. On a watch the wrist is the only confirmation the user gets when they start a
 * recording and immediately drop their arm, so it is not decoration.
 */
interface Haptics {
    /** Started. */
    fun click()

    /** Stopped — two taps, so it cannot be mistaken for a start that was felt late. */
    fun doubleClick()

    /** A highlight was kept — the lightest of the four, as the phone's highlight node gives (P3). */
    fun tick()

    /**
     * A recording reached the phone: its meta was acked, and only then (P4). Three short pulses — a pattern of its
     * own, so the end of a transfer that lands a minute after a stop is not felt as a start or a stop.
     */
    fun success()
}

class SystemHaptics(context: Context) : Haptics {

    private val vibrator: Vibrator =
        context.getSystemService(VibratorManager::class.java).defaultVibrator

    override fun click() = vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))

    override fun doubleClick() = vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))

    override fun tick() = vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))

    // Off, on, off, on, off, on — the platform has no predefined "success".
    override fun success() = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 25, 70, 25, 70, 25), -1))

    private fun vibrate(effect: VibrationEffect) {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(effect)
    }
}
