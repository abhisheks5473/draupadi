package com.draupadi.app.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Sound. Two very different jobs:
 *
 *  - [confirm] is the two-note chirp that says "it fired". It is short on
 *    purpose, so it does not sit on top of the evidence recording.
 *  - [siren] is the optional attention-grabber, off by default, for when
 *    being heard matters more than a clean audio track.
 */
object Alarm {

    private var ringtone: android.media.Ringtone? = null

    fun confirm(loud: Boolean) {
        try {
            val stream = if (loud) AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION
            val tone = ToneGenerator(stream, if (loud) 100 else 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
                } catch (_: Throwable) {
                }
            }, 420)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    tone.release()
                } catch (_: Throwable) {
                }
            }, 1300)
        } catch (_: Throwable) {
        }
    }

    fun siren(context: Context) {
        stopSiren()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: return
            val r = RingtoneManager.getRingtone(context, uri) ?: return
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) r.isLooping = true
            r.play()
            ringtone = r
        } catch (_: Throwable) {
        }
    }

    fun stopSiren() {
        try {
            ringtone?.stop()
        } catch (_: Throwable) {
        }
        ringtone = null
    }
}
