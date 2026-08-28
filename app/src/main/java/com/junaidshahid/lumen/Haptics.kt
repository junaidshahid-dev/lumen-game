package com.junaidshahid.lumen

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Short, quiet taps. The game is meant to be calming, so every effect here is
 * under 20 ms and well below full amplitude.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val available = vibrator?.hasVibrator() == true

    fun slide() = pulse(8, 45)

    fun merge(exp: Int) = pulse(if (exp >= 11) 22 else 14, if (exp >= 11) 150 else 95)

    fun tap() = pulse(10, 70)

    fun reject() = pulse(18, 60)

    private fun pulse(millis: Long, amplitude: Int) {
        if (!available) return
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(millis, amplitude.coerceIn(1, 255)))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(millis)
        }
    }
}
