package com.draupadi.app.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Vibration. The alert pattern is real morse code for SOS
 * (· · ·  — — —  · · ·) so a phone in a pocket is recognisable
 * without ever being looked at.
 */
object Buzz {

    val SOS = longArrayOf(
        0,
        140, 90, 140, 90, 140, 260,
        420, 120, 420, 120, 420, 260,
        140, 90, 140, 90, 140
    )

    val TAP = longArrayOf(0, 18)
    val CONFIRM = longArrayOf(0, 20, 60, 20, 60, 90)
    val WARN = longArrayOf(0, 45, 70, 45)
    val QUIET = longArrayOf(0, 30, 220, 30, 220, 30)

    private fun vib(context: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Throwable) {
        null
    }

    fun once(context: Context, pattern: LongArray) {
        try {
            vib(context)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Throwable) {
        }
    }

    fun repeat(context: Context, pattern: LongArray) {
        try {
            vib(context)?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } catch (_: Throwable) {
        }
    }

    fun stop(context: Context) {
        try {
            vib(context)?.cancel()
        } catch (_: Throwable) {
        }
    }
}
