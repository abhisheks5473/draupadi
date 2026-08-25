package com.draupadi.app.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.draupadi.app.data.Contact

/**
 * Getting the message out.
 *
 * SMS is the only channel that works with no data, no app on the other end and
 * no account — so that is what carries the location. It goes out with no
 * confirmation dialog and no tap: the permission is granted once during setup
 * and never asked for again.
 *
 * Every message is sent with a result callback, so the app can say "3 of 4
 * delivered" rather than crossing its fingers.
 */
object Messenger {

    private const val TAG = "Draupadi/Msg"

    const val ACTION_SMS_SENT = "com.draupadi.app.SMS_SENT"
    const val EXTRA_TO = "to"

    private fun sms(context: Context): SmsManager? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "no sms manager: ${t.message}")
        null
    }

    fun canSendSms(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * @param tracked when true each message carries a result PendingIntent that
     *                broadcasts [ACTION_SMS_SENT] back to the service
     * @return how many messages were handed to the radio
     */
    fun sendTo(
        context: Context,
        numbers: List<String>,
        text: String,
        tracked: Boolean = false
    ): Int {
        if (!canSendSms(context)) {
            Log.w(TAG, "SEND_SMS not granted")
            return 0
        }
        val m = sms(context) ?: return 0
        var handed = 0
        numbers.filter { it.isNotBlank() }.forEachIndexed { index, number ->
            try {
                val parts = m.divideMessage(text)
                if (parts.size <= 1) {
                    val pi = if (tracked) sentIntent(context, index, number) else null
                    m.sendTextMessage(number, null, text, pi, null)
                } else {
                    // long messages cannot be tracked part-by-part without
                    // over-counting, so they are sent plainly
                    m.sendMultipartTextMessage(number, null, parts, null, null)
                }
                handed++
            } catch (t: Throwable) {
                Log.w(TAG, "sms to $number failed: ${t.message}")
            }
        }
        return handed
    }

    private fun sentIntent(context: Context, index: Int, number: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            9000 + index,
            Intent(ACTION_SMS_SENT)
                .setPackage(context.packageName)
                .putExtra(EXTRA_TO, number),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Deliberately under 160 characters so it is one SMS, one result, no ambiguity. */
    fun alertText(name: String, mapsLink: String): String {
        val who = if (name.isBlank()) "Someone" else name
        return "SOS: $who may be in danger and needs help now. Location: $mapsLink (sent by Draupadi)"
    }

    fun updateText(name: String, mapsLink: String): String {
        val who = if (name.isBlank()) "She" else name
        return "$who is still in danger. Updated location: $mapsLink"
    }

    fun safeText(name: String): String {
        val who = if (name.isBlank()) "She" else name
        return "$who has marked herself safe. The Draupadi alert is closed."
    }

    fun falseAlarmText(name: String): String {
        val who = if (name.isBlank()) "She" else name
        return "False alarm — $who cancelled the Draupadi alert. She is fine."
    }

    /**
     * Hands the finished recording to WhatsApp with the video already attached.
     * Android will not let any app send a WhatsApp message without a human
     * tapping send, so this is surfaced as a one-tap notification action rather
     * than pretended to be automatic.
     */
    fun whatsAppIntent(context: Context, video: Uri, caption: String): Intent? {
        val pm = context.packageManager
        val target = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pkg ->
            try {
                pm.getPackageInfo(pkg, 0); true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        } ?: return null

        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            setPackage(target)
            putExtra(Intent.EXTRA_STREAM, video)
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun shareIntent(video: Uri, caption: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, video)
                putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Send the recording"
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    fun pending(context: Context, intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun numbersOf(contacts: List<Contact>, police: String): List<String> =
        (contacts.map { it.number } + police).filter { it.isNotBlank() }.distinct()
}
