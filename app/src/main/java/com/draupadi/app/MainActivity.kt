package com.draupadi.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.draupadi.app.core.AppState
import com.draupadi.app.core.ResultUi
import com.draupadi.app.data.Prefs
import com.draupadi.app.net.Cloud
import com.draupadi.app.service.GuardianService
import com.draupadi.app.ui.AlertScreen
import com.draupadi.app.ui.DraupadiTheme
import com.draupadi.app.ui.HomeScreen
import com.draupadi.app.ui.IncomingScreen
import com.draupadi.app.ui.ResultScreen
import com.draupadi.app.ui.SettingsScreen
import com.draupadi.app.ui.SetupScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    private val core = buildList<String> {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private var granted by mutableStateOf(false)
    private var alwaysLocation by mutableStateOf(false)

    private val askCore =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = hasCore()
            if (granted && Build.VERSION.SDK_INT >= 29) askBackground.launch(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
            if (granted) startGuardian()
        }

    private val askBackground =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun hasCore() = core.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** "Allow all the time" — without it Android stops sharing the position
     *  the moment the screen goes off, which is exactly when it matters. */
    private fun hasAlwaysLocation(): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            has(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun locationStatusText(): String = when {
        !has(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !has(Manifest.permission.ACCESS_COARSE_LOCATION) ->
            "Off — nobody can be sent to you"
        !hasAlwaysLocation() -> "Only while the app is open"
        else -> "Allow all the time — always on"
    }

    /** Android 11 and newer will not grant background location from a dialog;
     *  it has to be chosen in the app's own settings page. */
    private fun fixLocation() {
        if (Build.VERSION.SDK_INT == 29) {
            askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        } catch (_: Throwable) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        prefs = Prefs(this)
        Cloud.init(this)
        granted = hasCore()
        alwaysLocation = hasAlwaysLocation()
        if (prefs.setupDone && prefs.guardianOn) startGuardian()

        val activity = this
        setContent {
            DraupadiTheme {
                val alert by AppState.alert.collectAsState()
                val incoming by AppState.incoming.collectAsState()
                val result by AppState.result.collectAsState()
                val listening by AppState.listening.collectAsState()
                val micLevel by AppState.micLevel.collectAsState()
                val shakeLevel by AppState.shakeLevel.collectAsState()
                val shakeAt by AppState.shakeDetected.collectAsState()
                val heard by AppState.heard.collectAsState()
                val cloudStatus by AppState.cloudStatus.collectAsState()
                val locationSummary by AppState.locationSummary.collectAsState()
                val locationAt by AppState.locationAt.collectAsState()
                val locationOn by AppState.locationOn.collectAsState()

                var settingsOpen by remember { mutableStateOf(false) }
                var setupDone by remember { mutableStateOf(prefs.setupDone) }
                var contacts by remember { mutableStateOf(prefs.contacts) }
                var word by remember { mutableStateOf(prefs.safeWord) }
                var guardianOn by remember { mutableStateOf(prefs.guardianOn) }
                var shakeFlash by remember { mutableStateOf(false) }

                // the screen must not sleep while help is on its way
                LaunchedEffect(alert.active) {
                    if (alert.active) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                LaunchedEffect(shakeAt) {
                    if (shakeAt > 0L) {
                        shakeFlash = true
                        delay(2200)
                        shakeFlash = false
                    }
                }

                when {
                    !setupDone -> SetupScreen(
                        initialName = prefs.name,
                        initialWord = prefs.safeWord,
                        contacts = contacts,
                        onAddContact = { prefs.addContact(it); contacts = prefs.contacts },
                        onRemoveContact = { prefs.removeContact(it); contacts = prefs.contacts },
                        onAskPermissions = { askCore.launch(core) },
                        onBatterySettings = { openBatterySettings() },
                        permissionsGranted = granted,
                        onDone = { name, safeWord ->
                            prefs.name = name
                            prefs.safeWord = safeWord
                            prefs.setupDone = true
                            word = safeWord
                            setupDone = true
                            if (!granted) askCore.launch(core) else startGuardian()
                        }
                    )

                    alert.active -> AlertScreen(
                        state = alert,
                        cloudOn = Cloud.enabled,
                        policeNumber = prefs.policeNumber,
                        locationSummary = locationSummary,
                        onSafe = { GuardianService.send(activity, GuardianService.ACTION_SAFE) },
                        onCancel = { GuardianService.send(activity, GuardianService.ACTION_CANCEL) },
                        onCall = { callPolice() }
                    )

                    incoming.active -> IncomingScreen(
                        state = incoming,
                        onAccept = { GuardianService.send(activity, GuardianService.ACTION_ACCEPT) },
                        onDecline = { GuardianService.send(activity, GuardianService.ACTION_DISMISS) },
                        onNavigate = { openMaps(incoming.lat, incoming.lng) }
                    )

                    result.show -> ResultScreen(
                        state = result,
                        onDone = { AppState.result.value = ResultUi() }
                    )

                    settingsOpen -> SettingsScreen(
                        prefs = prefs,
                        cloudStatus = cloudStatus,
                        micLevel = micLevel,
                        shakeLevel = shakeLevel,
                        shakeFlash = shakeFlash,
                        heard = heard,
                        locationStatus = locationStatusText(),
                        locationAlways = alwaysLocation,
                        locationSummary = locationSummary,
                        locationAt = locationAt,
                        locationOn = locationOn,
                        onFixLocation = { fixLocation() },
                        onTestSos = {
                            settingsOpen = false
                            GuardianService.send(
                                activity, GuardianService.ACTION_SOS,
                                trigger = "test", silent = false, dryRun = true
                            )
                        },
                        onBack = {
                            settingsOpen = false
                            word = prefs.safeWord
                            guardianOn = prefs.guardianOn
                        },
                        onChanged = {
                            guardianOn = prefs.guardianOn
                            refreshGuardian()
                        }
                    )

                    else -> HomeScreen(
                        safeWord = word,
                        listening = listening,
                        guardianOn = guardianOn,
                        micLevel = micLevel,
                        onSos = {
                            if (!granted) askCore.launch(core)
                            else GuardianService.send(
                                activity, GuardianService.ACTION_SOS,
                                trigger = "button", silent = prefs.silentOn
                            )
                        },
                        onSettings = { settingsOpen = true }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        granted = hasCore()
        alwaysLocation = hasAlwaysLocation()
        // a permission change made in Settings needs the service to notice
        if (prefs.setupDone && prefs.guardianOn) {
            GuardianService.send(this, GuardianService.ACTION_REFRESH)
        }
    }

    private fun startGuardian() {
        if (!prefs.guardianOn) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, GuardianService::class.java))
        } catch (_: Throwable) {
        }
    }

    /** Settings take effect immediately rather than after a reboot. */
    private fun refreshGuardian() {
        try {
            if (prefs.guardianOn) {
                startGuardian()
                GuardianService.send(this, GuardianService.ACTION_REFRESH)
            } else {
                GuardianService.send(this, GuardianService.ACTION_STOP)
            }
        } catch (_: Throwable) {
        }
    }

    private fun callPolice() {
        val number = prefs.policeNumber.ifBlank { "112" }
        val intent = if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        } else {
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        }
        try {
            startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    private fun openMaps(lat: Double, lng: Double) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng&mode=w"))
            )
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng")))
            } catch (_: Throwable) {
            }
        }
    }

    /** Android will stop a listening service unless it is exempted. */
    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Throwable) {
        }
    }
}
