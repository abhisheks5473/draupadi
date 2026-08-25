package com.draupadi.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.draupadi.app.service.GuardianService

/** Notification buttons land here and are handed straight to the service. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        GuardianService.send(context, action)
    }
}
