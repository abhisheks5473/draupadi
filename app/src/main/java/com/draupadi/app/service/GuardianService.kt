package com.draupadi.app.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
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
import com.draupadi.app.core.Alarm
import com.draupadi.app.core.AlertUi
import com.draupadi.app.core.AppState
import com.draupadi.app.core.Buzz
import com.draupadi.app.core.Geo
import com.draupadi.app.core.IncomingUi
import com.draupadi.app.core.Locator
import com.draupadi.app.core.ResultUi
import com.draupadi.app.core.ShakeDetector
import com.draupadi.app.core.ShakeSensitivity
import com.draupadi.app.data.Prefs
import com.draupadi.app.net.Cloud
import com.draupadi.app.receiver.ActionReceiver
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * One service does everything.
 *
 * Splitting "listening" and "alerting" into two services would be tidier, but
 * Android 14 refuses to let a background app start a camera or microphone
 * service — and an SOS must never lose a race with a policy check. Because
 * this service is already running in the foreground when the safe word is
 * heard, it is allowed to pick up the camera immediately.
 */
class GuardianService : LifecycleService() {

    private lateinit var prefs: Prefs

    private var recognizer: SpeechRecognizer? = null
    private var wantListening = false
    private var consecutiveErrors = 0

    private var recorder: EvidenceRecorder? = null
    private var fused: FusedLocationProviderClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensors: SensorManager? = null
    private var shake: ShakeDetector? = null
    private var locator: Locator? = null

    /** Set when the first alert text had to go out without coordinates, so the
     *  moment a fix lands a corrected one follows straight away. */
    private var locationTextOwed = false

    private val main = Handler(Looper.getMainLooper())
    private val restartListening = Runnable { beginRecognition() }

    private var lastLocation: Location? = null
    private var alertId: String = ""
    private var alertJob: Job? = null
    private var alertReg: ListenerRegistration? = null
    private var inboxReg: ListenerRegistration? = null
    private var preciseReg: ListenerRegistration? = null
    private var incomingReg: ListenerRegistration? = null
    private var incomingId: String = ""

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)

        // an alert must never have to say "no location yet"
        if (prefs.hasLastLocation) {
            lastLocation = Location("saved").apply {
                latitude = prefs.lastLat
                longitude = prefs.lastLng
            }
            AppState.locationSummary.value = Locator.describe(lastLocation)
        }

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

        ContextCompat.registerReceiver(
            this, smsResult, IntentFilter(Messenger.ACTION_SMS_SENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        lifecycleScope.launch {
            Cloud.ensureSignedIn()
            startInbox()
        }

        startLocation()
        applySettings()
        main.post(shakeMeter)

        AppState.guardianRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SOS -> triggerAlert(
                intent.getStringExtra(EXTRA_TRIGGER) ?: "button",
                intent.getBooleanExtra(EXTRA_SILENT, prefs.silentOn),
                intent.getBooleanExtra(EXTRA_DRY, false)
            )
            ACTION_SAFE -> endAlert(markedSafe = true, cancelled = false)
            ACTION_CANCEL -> endAlert(markedSafe = false, cancelled = true)
            ACTION_ACCEPT -> acceptIncoming()
            ACTION_DISMISS -> dismissIncoming()
            ACTION_REFRESH -> applySettings()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppState.guardianRunning.value = false
        main.removeCallbacks(shakeMeter)
        stopListening()
        stopShake()
        alertJob?.cancel()
        alertReg?.remove(); inboxReg?.remove(); preciseReg?.remove(); incomingReg?.remove()
        recorder?.release()
        try {
            fused?.removeLocationUpdates(locationCallback)
        } catch (_: Throwable) {
        }
        locator?.stop()
        try {
            unregisterReceiver(smsResult)
        } catch (_: Throwable) {
        }
        try {
            wakeLock?.release()
        } catch (_: Throwable) {
        }
        Buzz.stop(this)
        Alarm.stopSiren()
        super.onDestroy()
    }

    /** Re-reads every toggle. Called on start and whenever settings change,
     *  so the switches take effect immediately instead of after a reboot. */
    private fun applySettings() {
        if (prefs.guardianOn && prefs.voiceOn) startListening() else stopListening()
        if (prefs.guardianOn && prefs.shakeOn) startShake() else stopShake()
        startLocation()
        if (prefs.guardianOn && prefs.respondOn) {
            lifecycleScope.launch { startInbox() }
        } else {
            inboxReg?.remove(); inboxReg = null
        }
        goForeground()
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

    private fun openApp(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        this, requestCode,
        Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        this, code,
        Intent(this, ActionReceiver::class.java).setAction(name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun idleNotification(): Notification {
        val text = when {
            !prefs.guardianOn -> "Guardian is off"
            prefs.voiceOn -> "Listening for “${prefs.safeWord}”"
            else -> "Ready — hold the button or shake the phone"
        }
        return NotificationCompat.Builder(this, CH_GUARD)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Draupadi")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(1))
            .build()
    }

    /** During an alert the ongoing notification becomes the receipt: it names
     *  exactly how many texts got out, and taps straight back to the screen. */
    private fun alertNotification(): Notification {
        val a = AppState.alert.value
        val title = if (a.dryRun) "TEST alert — nothing was sent" else "SOS ACTIVE"
        val body = buildString {
            append("${a.smsSent}/${a.smsTotal} texts sent")
            if (a.recording) append(" · recording")
            if (a.reached > 0) append(" · ${a.reached} phones alerted")
        }
        return NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(openApp(2), true)
            .setContentIntent(openApp(2))
            .addAction(0, "I am safe", action(ACTION_SAFE, 21))
            .build()
    }

    private fun serviceTypes(includeCamera: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var t = 0
        if (has(Manifest.permission.RECORD_AUDIO)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (includeCamera && has(Manifest.permission.CAMERA)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        return t
    }

    private fun goForeground() {
        val active = AppState.alert.value.active
        try {
            ServiceCompat.startForeground(
                this, NOTE_ID,
                if (active) alertNotification() else idleNotification(),
                serviceTypes(active)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed: ${t.message}")
        }
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------- the safe word

    /**
     * Android's recogniser stops after every utterance and after every silence
     * timeout, so an always-on keyword loop means restarting it constantly.
     * The old version destroyed and rebuilt the whole recogniser each round,
     * which is why the status indicator flickered on and off. Now one instance
     * is kept alive and simply restarted, and the "listening" flag stays
     * steady for as long as the loop is running.
     */
    private fun startListening() {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            AppState.listening.value = false
            return
        }
        if (AppState.alert.value.active) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "no speech recognition on this device")
            AppState.listening.value = false
            return
        }
        wantListening = true
        AppState.listening.value = true
        main.post { beginRecognition() }
    }

    private fun beginRecognition() {
        if (!wantListening || AppState.alert.value.active) return
        try {
            if (recognizer == null) {
                val r = if (Build.VERSION.SDK_INT >= 33 &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
                ) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(this)
                }
                r.setRecognitionListener(listener)
                recognizer = r
            }
            recognizer?.startListening(recognizerIntent())
        } catch (t: Throwable) {
            Log.w(TAG, "recogniser failed: ${t.message}")
            rebuildRecognizer()
            scheduleRestart(2000)
        }
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        if (Build.VERSION.SDK_INT >= 33) {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }

    private fun scheduleRestart(ms: Long) {
        main.removeCallbacks(restartListening)
        main.postDelayed(restartListening, ms)
    }

    private fun rebuildRecognizer() {
        val r = recognizer
        recognizer = null
        main.post {
            try {
                r?.cancel(); r?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    private fun stopListening() {
        wantListening = false
        AppState.listening.value = false
        AppState.micLevel.value = 0f
        main.removeCallbacks(restartListening)
        rebuildRecognizer()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            consecutiveErrors = 0
        }

        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        /** Drives the live level bar on the home screen — visible proof that
         *  the microphone really is open. */
        override fun onRmsChanged(rmsdB: Float) {
            AppState.micLevel.value = ((rmsdB + 2f) / 11f).coerceIn(0f, 1f)
        }

        override fun onError(error: Int) {
            AppState.micLevel.value = 0f
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // completely normal in a quiet room — go straight round again
                    consecutiveErrors = 0
                    scheduleRestart(200)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRestart(1200)
                SpeechRecognizer.ERROR_CLIENT -> {
                    rebuildRecognizer()
                    scheduleRestart(900)
                }
                else -> {
                    consecutiveErrors++
                    if (consecutiveErrors > 8) rebuildRecognizer()
                    scheduleRestart(if (consecutiveErrors > 4) 5000 else 1000)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) = check(partialResults)

        override fun onResults(results: Bundle?) {
            check(results)
            scheduleRestart(200)
        }

        private fun check(bundle: Bundle?) {
            val hits = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            val heard = hits.joinToString(" ").lowercase(Locale.getDefault())
            if (heard.isNotBlank()) AppState.heard.value = heard
            val word = prefs.safeWord.lowercase(Locale.getDefault())
            if (word.isNotBlank() && heard.contains(word)) {
                triggerAlert("voice", prefs.silentOn, false)
            }
        }
    }

    // ------------------------------------------------------------- shaking

    /** Feeds the live bar on the self-test screen. One instance, started once. */
    private val shakeMeter = object : Runnable {
        override fun run() {
            AppState.shakeLevel.value = shake?.level ?: 0f
            main.postDelayed(this, 100)
        }
    }

    private fun startShake() {
        stopShake()
        sensors = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            Log.w(TAG, "no accelerometer on this phone")
            return
        }
        val d = ShakeDetector(ShakeSensitivity.of(prefs.shakeSensitivity)) {
            AppState.shakeDetected.value = System.currentTimeMillis()
            if (!AppState.alert.value.active) triggerAlert("shake", prefs.silentOn, false)
        }
        shake = d
        sensors?.registerListener(d, accel, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopShake() {
        shake?.let { sensors?.unregisterListener(it) }
        shake = null
        AppState.shakeLevel.value = 0f
    }

    // ------------------------------------------------------------ location

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onFix(it) }
        }
    }

    /**
     * Every fix from every provider lands here. Google's fused provider and
     * the framework's own GPS and network providers all feed this, so if one
     * goes quiet the others still answer the only question that matters.
     */
    private fun onFix(loc: Location) {
        if (!Locator.isBetterFix(loc, lastLocation)) return
        lastLocation = loc
        prefs.lastLat = loc.latitude
        prefs.lastLng = loc.longitude
        AppState.locationSummary.value = Locator.describe(loc)
        AppState.locationAt.value = System.currentTimeMillis()

        val a = AppState.alert.value
        if (a.active) {
            AppState.alert.value = a.copy(locationFixed = true)

            // the first text went out before there was anything to send —
            // correct it now rather than at the two-minute mark
            if (locationTextOwed && !a.dryRun) {
                locationTextOwed = false
                Messenger.sendTo(
                    this,
                    Messenger.numbersOf(prefs.contacts, prefs.policeNumber),
                    Messenger.alertText(prefs.name, Geo.mapsLink(loc.latitude, loc.longitude))
                )
            }
            if (!a.dryRun) {
                lifecycleScope.launch {
                    Cloud.pushPrecise(alertId, loc.latitude, loc.longitude, loc.accuracy)
                }
            }
        } else {
            lifecycleScope.launch { Cloud.heartbeat(loc.latitude, loc.longitude, prefs.name) }
        }
    }

    private fun startLocation() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !has(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) return
        try {
            fused?.lastLocation?.addOnSuccessListener {
                it?.let { l ->
                    lastLocation = l
                    prefs.lastLat = l.latitude
                    prefs.lastLng = l.longitude
                }
            }
            // always on: the position is kept current in the background so an
            // alert never has to wait for a first fix
            val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
                .setMinUpdateIntervalMillis(10_000L)
                .setWaitForAccurateLocation(false)
                .build()
            fused?.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        } catch (t: Throwable) {
            Log.w(TAG, "fused location unavailable: ${t.message}")
        }

        // and the framework's own providers, which need no Play Services
        val l = locator ?: Locator(this) { fix -> onFix(fix) }.also { locator = it }
        l.start(fast = false)
        AppState.locationOn.value = l.anyProviderEnabled()
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
        val l = locator ?: Locator(this) { fix -> onFix(fix) }.also { locator = it }
        l.start(fast = true)
        AppState.locationOn.value = l.anyProviderEnabled()
    }

    // ---------------------------------------------------------- sms result

    /** Real delivery counts, so the screen can say "3 of 4 sent" honestly. */
    private val smsResult = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val a = AppState.alert.value
            if (!a.active) return
            AppState.alert.value = if (resultCode == Activity.RESULT_OK) {
                a.copy(smsSent = a.smsSent + 1)
            } else {
                a.copy(smsFailed = a.smsFailed + 1)
            }
            goForeground()
        }
    }

    // --------------------------------------------------------------- alert

    private fun triggerAlert(trigger: String, silent: Boolean, dryRun: Boolean) {
        if (AppState.alert.value.active) return

        stopListening()
        AppState.result.value = ResultUi()

        val numbers = Messenger.numbersOf(prefs.contacts, prefs.policeNumber)
        AppState.alert.value = AlertUi(
            active = true, seconds = 0, trigger = trigger, silent = silent, dryRun = dryRun,
            smsTotal = if (dryRun) 0 else numbers.size,
            cancelSecs = CANCEL_WINDOW,
            note = if (dryRun) "Test" else "Sending"
        )

        // make it unmistakable that it fired
        Buzz.once(this, if (silent) Buzz.QUIET else Buzz.SOS)
        if (!silent) Alarm.confirm(prefs.loudSiren)
        if (!silent && prefs.loudSiren) Alarm.siren(this)

        goForeground()
        showAlertScreen()
        sharpenLocation()

        // 1. the text goes first — the only channel that needs nothing from
        //    the other side: no app, no data, no account
        if (!dryRun) {
            val loc = lastLocation
            locationTextOwed = loc == null
            val link = if (loc != null) Geo.mapsLink(loc.latitude, loc.longitude)
            else "locating now, an updated link follows in seconds"
            val handed = Messenger.sendTo(this, numbers, Messenger.alertText(prefs.name, link), tracked = true)
            AppState.alert.value = AppState.alert.value.copy(
                smsTotal = numbers.size,
                note = if (handed == 0) "Could not send texts" else "Recording"
            )
        }

        // 2. the camera
        if (prefs.autoRecord) {
            recorder = EvidenceRecorder(this, this).also { rec ->
                rec.start(useFrontCamera = false) { uri -> onRecordingSaved(uri) }
            }
            AppState.alert.value = AppState.alert.value.copy(recording = true)
        }

        // 3. the neighbourhood
        alertJob = lifecycleScope.launch { runAlert(trigger, dryRun) }
    }

    /** Brings the red screen up even from a locked, dark phone. */
    private fun showAlertScreen() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        } catch (t: Throwable) {
            // Android blocks background activity starts; the full-screen
            // notification posted alongside is the sanctioned fallback
            Log.w(TAG, "could not open the screen directly: ${t.message}")
        }
    }

    private suspend fun runAlert(trigger: String, dryRun: Boolean) {
        val loc = lastLocation
        var lat = loc?.latitude ?: 0.0
        var lng = loc?.longitude ?: 0.0

        if (!dryRun && Cloud.enabled && loc != null) {
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
            val now = AppState.alert.value
            AppState.alert.value = now.copy(
                seconds = seconds,
                cancelSecs = if (now.cancelSecs > 0) now.cancelSecs - 1 else 0
            )

            if (seconds % 5 == 0) goForeground()

            lastLocation?.let { lat = it.latitude; lng = it.longitude }
            if (dryRun) continue

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
            if (seconds % 120 == 0 && smsUpdates < 3 && lastLocation != null) {
                smsUpdates++
                Messenger.sendTo(
                    this@GuardianService,
                    Messenger.numbersOf(prefs.contacts, prefs.policeNumber),
                    Messenger.updateText(prefs.name, Geo.mapsLink(lat, lng))
                )
            }
        }
    }

    private fun endAlert(markedSafe: Boolean, cancelled: Boolean) {
        val was = AppState.alert.value
        if (!was.active) return

        AppState.alert.value = was.copy(active = false, note = "Saving")
        alertJob?.cancel()
        alertReg?.remove(); alertReg = null
        Buzz.stop(this)
        Alarm.stopSiren()
        Buzz.once(this, Buzz.CONFIRM)

        recorder?.stop()

        if (!was.dryRun) {
            val numbers = Messenger.numbersOf(prefs.contacts, prefs.policeNumber)
            if (cancelled) {
                Messenger.sendTo(this, numbers, Messenger.falseAlarmText(prefs.name))
            } else if (markedSafe) {
                Messenger.sendTo(this, numbers, Messenger.safeText(prefs.name))
            }
        }

        val id = alertId
        alertId = ""
        if (!was.dryRun) lifecycleScope.launch { if (id.isNotEmpty()) Cloud.closeAlert(id) }

        AppState.result.value = ResultUi(
            show = true,
            cancelled = cancelled,
            dryRun = was.dryRun,
            seconds = was.seconds,
            smsSent = was.smsSent,
            smsTotal = was.smsTotal,
            reached = was.reached,
            savedVideo = was.savedVideo
        )

        goForeground()
        startLocation()
        main.postDelayed({ applySettings() }, 1500)
    }

    /** The clip is already in the Gallery by the time this runs. */
    private fun onRecordingSaved(uri: Uri?) {
        val a = AppState.alert.value
        AppState.alert.value = a.copy(
            recording = false,
            savedVideo = uri?.toString(),
            note = if (uri != null) "Saved to Gallery" else "Recording failed"
        )
        AppState.result.value = AppState.result.value.copy(savedVideo = uri?.toString())
        if (uri == null || a.dryRun) return

        lifecycleScope.launch {
            val link = if (alertId.isNotEmpty()) Cloud.uploadVideo(alertId, uri) else null
            if (link != null) {
                Messenger.sendTo(
                    this@GuardianService,
                    Messenger.numbersOf(prefs.contacts, prefs.policeNumber),
                    "Draupadi recording: $link"
                )
            }
            notifyVideoReady(uri, link)
        }
    }

    private fun notifyVideoReady(uri: Uri, cloudLink: String?) {
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
            .setOnlyAlertOnce(true)
            .setContentIntent(Messenger.pending(this, send, 42))
            .build()
        nm.notify(NOTE_VIDEO, note)
    }

    // ---------------------------------------------------- being a neighbour

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
        val note = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Someone ${Geo.pretty(distanceM.toDouble())} away needs help")
            .setContentText("$fromName triggered an SOS. Only accept if you can actually go.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(openApp(7), true)
            .setContentIntent(openApp(7))
            .addAction(0, "I can help", action(ACTION_ACCEPT, 8))
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
                AppState.incoming.value =
                    AppState.incoming.value.copy(lat = lat, lng = lng, unlocked = true)
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

        /** How long a fresh alert can still be called a false alarm. */
        const val CANCEL_WINDOW = 10

        const val ACTION_SOS = "com.draupadi.app.SOS"
        const val ACTION_SAFE = "com.draupadi.app.SAFE"
        const val ACTION_CANCEL = "com.draupadi.app.CANCEL"
        const val ACTION_ACCEPT = "com.draupadi.app.ACCEPT"
        const val ACTION_DISMISS = "com.draupadi.app.DISMISS"
        const val ACTION_REFRESH = "com.draupadi.app.REFRESH"
        const val ACTION_STOP = "com.draupadi.app.STOP"
        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_SILENT = "silent"
        const val EXTRA_DRY = "dry"

        fun send(
            context: Context,
            action: String,
            trigger: String = "button",
            silent: Boolean = false,
            dryRun: Boolean = false
        ) {
            val i = Intent(context, GuardianService::class.java).setAction(action)
                .putExtra(EXTRA_TRIGGER, trigger)
                .putExtra(EXTRA_SILENT, silent)
                .putExtra(EXTRA_DRY, dryRun)
            try {
                ContextCompat.startForegroundService(context, i)
            } catch (t: Throwable) {
                Log.e(TAG, "could not reach the service: ${t.message}")
            }
        }
    }
}
