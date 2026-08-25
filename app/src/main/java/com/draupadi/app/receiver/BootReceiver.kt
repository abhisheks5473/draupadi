package com.draupadi.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.draupadi.app.data.Prefs
import com.draupadi.app.service.GuardianService

/** Comes back after a reboot. A guardian that forgets is not a guardian. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = Prefs(context)
        if (!prefs.setupDone || !prefs.guardianOn) return
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, GuardianService::class.java)
            )
        } catch (_: Throwable) {
        }
    }
}
