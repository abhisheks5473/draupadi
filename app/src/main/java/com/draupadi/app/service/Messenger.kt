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
 * no account — so that is what carries the location. It is sent with no
 * confirmation dialog and no tap: permission is granted once during setup, and
 * never asked for again. Someone being followed should not have to answer a
 * system prompt.
 */
object Messenger {

    private const val TAG = "Draupadi/Msg"

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

    /** @return how many messages actually went out */
    fun sendTo(context: Context, numbers: List<String>, text: String): Int {
        if (!canSendSms(context)) {
            Log.w(TAG, "SEND_SMS not granted")
            return 0
        }
        val m = sms(context) ?: return 0
        var sent = 0
        numbers.filter { it.isNotBlank() }.forEach { number ->
            try {
                val parts = m.divideMessage(text)
                if (parts.size <= 1) {
                    m.sendTextMessage(number, null, text, null, null)
                } else {
                    m.sendMultipartTextMessage(number, null, parts, null, null)
                }
                sent++
            } catch (t: Throwable) {
                Log.w(TAG, "sms to $number failed: ${t.message}")
            }
        }
        return sent
    }

    fun alertText(name: String, mapsLink: String, first: Boolean): String {
        val who = if (name.isBlank()) "Someone" else name
        return if (first) {
            "EMERGENCY. $who has triggered an SOS and may be in danger. " +
                "Live location: $mapsLink . Please call her and reach her now. " +
                "Sent automatically by Draupadi."
        } else {
            "$who is still in danger. Updated location: $mapsLink"
        }
    }

    fun safeText(name: String): String {
        val who = if (name.isBlank()) "She" else name
        return "$who has marked herself safe. The Draupadi alert is closed."
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

    /** Falls back to the system share sheet when WhatsApp is not installed. */
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
