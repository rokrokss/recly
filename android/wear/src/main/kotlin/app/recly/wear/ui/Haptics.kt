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
}

class SystemHaptics(context: Context) : Haptics {

    private val vibrator: Vibrator =
        context.getSystemService(VibratorManager::class.java).defaultVibrator

    override fun click() = vibrate(VibrationEffect.EFFECT_CLICK)

    override fun doubleClick() = vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)

    private fun vibrate(effect: Int) {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createPredefined(effect))
    }
}
