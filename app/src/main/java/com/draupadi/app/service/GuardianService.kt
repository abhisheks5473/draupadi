package com.draupadi.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.draupadi.app.MainActivity
import com.draupadi.app.R
import com.draupadi.app.core.AlertUi
import com.draupadi.app.core.AppState
import com.draupadi.app.core.Buzz
import com.draupadi.app.core.Geo
import com.draupadi.app.core.IncomingUi
import com.draupadi.app.data.Prefs
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.ListenerRegistration
import com.draupadi.app.net.Cloud
import com.draupadi.app.receiver.ActionReceiver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.sqrt

/**
 * One service does everything.
 *
 * Splitting "listening" and "alerting" into two services would be tidier, but
 * Android 14 refuses to let a background app start a camera or microphone
 * service — and an SOS must never lose a race with a policy check. Because
 * this service is already running in the foreground when the safe word is
 * heard, it is allowed to pick up the camera immediately.
 */
class GuardianService : LifecycleService(), SensorEventListener {

    private lateinit var prefs: Prefs

    private var recognizer: SpeechRecognizer? = null
    private var recorder: EvidenceRecorder? = null
    private var fused: FusedLocationProviderClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensors: SensorManager? = null

    private val main = Handler(Looper.getMainLooper())

    private var lastLocation: Location? = null
    private var alertId: String = ""
    private var alertJob: Job? = null
    private var alertReg: ListenerRegistration? = null
    private var inboxReg: ListenerRegistration? = null
    private var preciseReg: ListenerRegistration? = null
    private var incomingReg: ListenerRegistration? = null
    private var incomingId: String = ""

    private var shakeHits = 0
    private var lastShakeAt = 0L
    private var restarting = false

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannels()
        goForeground()

        wakeLock = try {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "draupadi:guardian")
                .also { it.setReferenceCounted(false); it.acquire(12 * 60 * 60 * 1000L) }
        } catch (_: Throwable) {
            null
        }

        fused = LocationServices.getFusedLocationProviderClient(this)
        Cloud.init(this)

        lifecycleScope.launch {
            Cloud.ensureSignedIn()
            startInbox()
        }

        startLocation()
        startShake()
        startListening()

        AppState.guardianRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SOS -> triggerAlert(
                intent.getStringExtra(EXTRA_TRIGGER) ?: "button",
                intent.getBooleanExtra(EXTRA_SILENT, prefs.silentOn)
            )
            ACTION_SAFE -> endAlert(markedSafe = true)
            ACTION_ACCEPT -> acceptIncoming()
            ACTION_DISMISS -> dismissIncoming()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppState.guardianRunning.value = false
        AppState.listening.value = false
        stopListening()
        alertJob?.cancel()
        alertReg?.remove(); inboxReg?.remove(); preciseReg?.remove(); incomingReg?.remove()
        recorder?.release()
        try {
            fused?.removeLocationUpdates(locationCallback)
        } catch (_: Throwable) {
        }
        sensors?.unregisterListener(this)
        try {
            wakeLock?.release()
        } catch (_: Throwable) {
        }
        Buzz.stop(this)
        super.onDestroy()
    }

    // --------------------------------------------------------- notification

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_GUARD, getString(R.string.channel_guardian), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, getString(R.string.channel_alert), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    enableVibration(true)
                    vibrationPattern = Buzz.SOS
                    setBypassDnd(true)
                }
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun guardianNotification(text: String) =
        NotificationCompat.Builder(this, CH_GUARD)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Draupadi")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp())
            .build()

    private fun serviceTypes(includeCamera: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var t = 0
        if (has(Manifest.permission.RECORD_AUDIO)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (includeCamera && has(Manifest.permission.CAMERA)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        return t
    }

    private fun goForeground(text: String = "Listening for your safe word", camera: Boolean = false) {
        try {
            ServiceCompat.startForeground(this, NOTE_ID, guardianNotification(text), serviceTypes(camera))
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed: ${t.message}")
        }
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------ the safe word

    private fun startListening() {
        if (!prefs.guardianOn || !prefs.voiceOn) return
        if (!has(Manifest.permission.RECORD_AUDIO)) return
        if (AppState.alert.value.active) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "no speech recognition on this device")
            return
        }
        main.post {
            try {
                stopListening()
                val r = if (Build.VERSION.SDK_INT >= 33 &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
                ) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(this)
                }
                r.setRecognitionListener(listener)
                r.startListening(recognizerIntent())
                recognizer = r
                AppState.listening.value = true
            } catch (t: Throwable) {
                Log.w(TAG, "recognizer failed: ${t.message}")
                AppState.listening.value = false
                scheduleRestart(4000)
            }
        }
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        if (Build.VERSION.SDK_INT >= 33) {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }

    private fun stopListening() {
        AppState.listening.value = false
        val r = recognizer ?: return
        recognizer = null
        main.post {
            try {
                r.stopListening(); r.cancel(); r.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    private fun scheduleRestart(ms: Long) {
        if (restarting) return
        restarting = true
        main.postDelayed({
            restarting = false
            if (!AppState.alert.value.active) startListening()
        }, ms)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            AppState.listening.value = false
            // NO_MATCH and SPEECH_TIMEOUT are normal in a quiet room
            scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 2500 else 900)
        }

        override fun onPartialResults(partialResults: Bundle?) = check(partialResults)
        override fun onResults(results: Bundle?) {
            check(results)
            scheduleRestart(500)
        }

        private fun check(bundle: Bundle?) {
            val hits = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            val heard = hits.joinToString(" ").lowercase(Locale.getDefault())
            if (heard.isNotBlank()) AppState.heard.value = heard
            val word = prefs.safeWord.lowercase(Locale.getDefault())
            if (word.isNotBlank() && heard.contains(word)) {
                triggerAlert("voice", prefs.silentOn)
            }
        }
    }

    // ------------------------------------------------------------- shaking

    private fun startShake() {
        if (!prefs.shakeOn) return
        sensors = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensors?.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        if (AppState.alert.value.active) return
        val g = sqrt(
            (event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]).toDouble()
        )
        val now = System.currentTimeMillis()
        if (g > 26) {
            if (now - lastShakeAt > 3000) shakeHits = 0
            if (now - lastShakeAt > 180) {
                shakeHits++
                lastShakeAt = now
            }
            if (shakeHits >= 4) {
                shakeHits = 0
                triggerAlert("shake", prefs.silentOn)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ------------------------------------------------------------ location

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastLocation = loc
            if (AppState.alert.value.active) {
                AppState.alert.value = AppState.alert.value.copy(locationFixed = true)
                lifecycleScope.launch {
                    Cloud.pushPrecise(alertId, loc.latitude, loc.longitude, loc.accuracy)
                }
            } else {
                lifecycleScope.launch { Cloud.heartbeat(loc.latitude, loc.longitude, prefs.name) }
            }
        }
    }

    private fun startLocation() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !has(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) return
        try {
            fused?.lastLocation?.addOnSuccessListener { it?.let { l -> lastLocation = l } }
            val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L)
                .setMinUpdateIntervalMillis(20_000L)
                .build()
            fused?.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        } catch (t: Throwable) {
            Log.w(TAG, "location unavailable: ${t.message}")
        }
    }

    private fun sharpenLocation() {
        try {
            fused?.removeLocationUpdates(locationCallback)
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4_000L)
                .setMinUpdateIntervalMillis(2_000L)
                .build()
            fused?.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        } catch (_: Throwable) {
        }
    }

    // --------------------------------------------------------------- alert

    private fun triggerAlert(trigger: String, silent: Boolean) {
        if (AppState.alert.value.active) return

        stopListening()
        AppState.alert.value = AlertUi(
            active = true, seconds = 0, trigger = trigger, silent = silent,
            note = "Sending"
        )
        goForeground("SOS active — recording and alerting", camera = true)
        Buzz.once(this, if (silent) Buzz.QUIET else Buzz.SOS)
        sharpenLocation()

        // 1. the text goes first. It is the only channel that needs nothing
        //    from the other side: no app, no data, no account.
        val loc = lastLocation
        val link = if (loc != null) Geo.mapsLink(loc.latitude, loc.longitude) else "acquiring GPS…"
        val numbers = Messenger.numbersOf(prefs.contacts, prefs.policeNumber)
        val sent = Messenger.sendTo(this, numbers, Messenger.alertText(prefs.name, link, true))
        AppState.alert.value = AppState.alert.value.copy(smsSent = sent, note = "Recording")

        // 2. the camera
        recorder = EvidenceRecorder(this, this).also { rec ->
            rec.start(useFrontCamera = false) { uri -> onRecordingSaved(uri) }
        }
        AppState.alert.value = AppState.alert.value.copy(recording = true)

        // 3. the neighbourhood
        alertJob = lifecycleScope.launch { runAlert(trigger) }
    }

    private suspend fun runAlert(trigger: String) {
        val loc = lastLocation
        var lat = loc?.latitude ?: 0.0
        var lng = loc?.longitude ?: 0.0

        if (Cloud.enabled && loc != null) {
            Cloud.ensureSignedIn()
            val id = Cloud.createAlert(prefs.name, lat, lng, trigger)
            if (id != null) {
                alertId = id
                AppState.alert.value = AppState.alert.value.copy(id = id, cloudOk = true)
                Cloud.pushPrecise(id, lat, lng, loc.accuracy)
                val reached = Cloud.fanOut(id, lat, lng, 1.0, prefs.name)
                Cloud.setRadius(id, 1, reached)
                AppState.alert.value = AppState.alert.value.copy(reached = reached)
                alertReg = Cloud.watchAlert(id) { accepted, _ ->
                    AppState.alert.value = AppState.alert.value.copy(
                        accepted = accepted,
                        unlocked = accepted >= AppState.UNLOCK_AT
                    )
                }
            }
        }

        var seconds = 0
        var snapshotIndex = 0
        var smsUpdates = 0
        while (AppState.alert.value.active) {
            delay(1000)
            seconds++
            AppState.alert.value = AppState.alert.value.copy(seconds = seconds)

            if (seconds % 5 == 0) goForeground("SOS active · ${seconds}s — recording", camera = true)

            lastLocation?.let { lat = it.latitude; lng = it.longitude }

            // a still every 8 seconds, so evidence exists in the cloud even if
            // the phone does not survive long enough to finish the video
            if (seconds % 8 == 0 && Cloud.enabled && alertId.isNotEmpty()) {
                val index = snapshotIndex++
                recorder?.snapshot { bytes ->
                    lifecycleScope.launch { Cloud.uploadSnapshot(alertId, index, bytes) }
                }
            }

            // the ring grows until enough people have answered
            val target = when {
                seconds >= 75 -> 5
                seconds >= 45 -> 3
                seconds >= 20 -> 2
                else -> 1
            }
            if (target != AppState.alert.value.radiusKm && Cloud.enabled && alertId.isNotEmpty()) {
                val reached = Cloud.fanOut(alertId, lat, lng, target.toDouble(), prefs.name)
                Cloud.setRadius(alertId, target, reached)
                AppState.alert.value = AppState.alert.value.copy(radiusKm = target, reached = reached)
            }

            // a fresh location by text every two minutes, three times over
            if (seconds > 0 && seconds % 120 == 0 && smsUpdates < 3 && lastLocation != null) {
                smsUpdates++
                Messenger.sendTo(
                    this@GuardianService,
                    Messenger.numbersOf(prefs.contacts, prefs.policeNumber),
                    Messenger.alertText(prefs.name, Geo.mapsLink(lat, lng), false)
                )
            }
        }
    }

    private fun endAlert(markedSafe: Boolean) {
        if (!AppState.alert.value.active) return
        val was = AppState.alert.value
        AppState.alert.value = was.copy(active = false, note = "Saving")
        alertJob?.cancel()
        alertReg?.remove(); alertReg = null
        Buzz.stop(this)
        Buzz.once(this, Buzz.CONFIRM)

        recorder?.stop()

        if (markedSafe) {
            Messenger.sendTo(
                this,
                Messenger.numbersOf(prefs.contacts, prefs.policeNumber),
                Messenger.safeText(prefs.name)
            )
        }
        val id = alertId
        alertId = ""
        lifecycleScope.launch { if (id.isNotEmpty()) Cloud.closeAlert(id) }

        goForeground()
        startLocation()
        main.postDelayed({ startListening() }, 1500)
    }

    /** The clip is already in the Gallery by the time this runs. */
    private fun onRecordingSaved(uri: android.net.Uri?) {
        AppState.alert.value = AppState.alert.value.copy(
            recording = false,
            savedVideo = uri?.toString(),
            note = if (uri != null) "Saved to Gallery" else "Recording failed"
        )
        if (uri == null) return

        lifecycleScope.launch {
            val link = if (alertId.isNotEmpty()) Cloud.uploadVideo(alertId, uri) else null
            // The clip itself cannot travel by SMS, but a link to it can — and
            // that needs no tap from someone who has just been through this.
            if (link != null) {
                Messenger.sendTo(
                    this@GuardianService,
                    Messenger.numbersOf(prefs.contacts, prefs.policeNumber),
                    "Draupadi recording from ${prefs.name.ifBlank { "the alert" }}: $link"
                )
            }
            notifyVideoReady(uri, link)
        }
    }

    private fun notifyVideoReady(uri: android.net.Uri, cloudLink: String?) {
        val caption = buildString {
            append("Draupadi recording from my SOS.")
            lastLocation?.let { append(" Location: ${Geo.mapsLink(it.latitude, it.longitude)}") }
            if (cloudLink != null) append(" Backup: $cloudLink")
        }
        val send = Messenger.whatsAppIntent(this, uri, caption) ?: Messenger.shareIntent(uri, caption)
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val note = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Recording saved to your Gallery")
            .setContentText("Tap to send it to your people on WhatsApp")
            .setAutoCancel(true)
            .setContentIntent(Messenger.pending(this, send, 42))
            .build()
        nm.notify(NOTE_VIDEO, note)
    }

    // ----------------------------------------------------- being a neighbour

    private fun startInbox() {
        if (!prefs.respondOn) return
        inboxReg?.remove()
        inboxReg = Cloud.watchInbox { id, fromName, distanceM, lat, lng ->
            if (AppState.alert.value.active) return@watchInbox
            if (incomingId == id) return@watchInbox
            incomingId = id
            AppState.incoming.value = IncomingUi(
                active = true, id = id, fromName = fromName,
                distanceM = distanceM, lat = lat, lng = lng
            )
            Buzz.repeat(this, Buzz.SOS)
            notifyIncoming(fromName, distanceM)
            incomingReg?.remove()
            incomingReg = Cloud.watchAlert(id) { accepted, status ->
                AppState.incoming.value = AppState.incoming.value.copy(
                    acceptedCount = accepted,
                    unlocked = accepted >= AppState.UNLOCK_AT,
                    closed = status != "active"
                )
                if (status != "active") Buzz.stop(this)
            }
        }
    }

    private fun notifyIncoming(fromName: String, distanceM: Int) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            this, 7,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val accept = PendingIntent.getBroadcast(
            this, 8,
            Intent(this, ActionReceiver::class.java).setAction(ACTION_ACCEPT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val note = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Someone ${Geo.pretty(distanceM.toDouble())} away needs help")
            .setContentText("$fromName triggered an SOS. Only accept if you can actually go.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(0, "I can help", accept)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTE_INCOMING, note)
    }

    private fun acceptIncoming() {
        val id = AppState.incoming.value.id
        if (id.isBlank()) return
        Buzz.stop(this)
        Buzz.once(this, Buzz.CONFIRM)
        AppState.incoming.value = AppState.incoming.value.copy(accepted = true)
        lifecycleScope.launch {
            Cloud.accept(id)
            preciseReg?.remove()
            preciseReg = Cloud.watchPrecise(id) { lat, lng ->
                AppState.incoming.value = AppState.incoming.value.copy(lat = lat, lng = lng, unlocked = true)
            }
        }
    }

    private fun dismissIncoming() {
        val id = AppState.incoming.value.id
        Buzz.stop(this)
        AppState.incoming.value = IncomingUi()
        incomingId = ""
        preciseReg?.remove(); preciseReg = null
        incomingReg?.remove(); incomingReg = null
        getSystemService(NotificationManager::class.java)?.cancel(NOTE_INCOMING)
        lifecycleScope.launch { if (id.isNotEmpty()) Cloud.clearInbox(id) }
    }

    companion object {
        private const val TAG = "Draupadi/Svc"
        private const val CH_GUARD = "guardian"
        private const val CH_ALERT = "alerts"
        private const val NOTE_ID = 1
        private const val NOTE_INCOMING = 2
        private const val NOTE_VIDEO = 3

        const val ACTION_SOS = "com.draupadi.app.SOS"
        const val ACTION_SAFE = "com.draupadi.app.SAFE"
        const val ACTION_ACCEPT = "com.draupadi.app.ACCEPT"
        const val ACTION_DISMISS = "com.draupadi.app.DISMISS"
        const val ACTION_STOP = "com.draupadi.app.STOP"
        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_SILENT = "silent"

        fun send(context: Context, action: String, trigger: String = "button", silent: Boolean = false) {
            val i = Intent(context, GuardianService::class.java).setAction(action)
                .putExtra(EXTRA_TRIGGER, trigger)
                .putExtra(EXTRA_SILENT, silent)
            try {
                ContextCompat.startForegroundService(context, i)
            } catch (t: Throwable) {
                Log.e(TAG, "could not reach the service: ${t.message}")
            }
        }
    }
}
