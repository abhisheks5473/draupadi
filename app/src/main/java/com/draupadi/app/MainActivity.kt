package com.draupadi.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.draupadi.app.core.AppState
import com.draupadi.app.data.Prefs
import com.draupadi.app.service.GuardianService
import com.draupadi.app.ui.AlertScreen
import com.draupadi.app.ui.DraupadiTheme
import com.draupadi.app.ui.HomeScreen
import com.draupadi.app.ui.IncomingScreen
import com.draupadi.app.ui.SettingsScreen
import com.draupadi.app.ui.SetupScreen
import com.draupadi.app.net.Cloud

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        prefs = Prefs(this)
        Cloud.init(this)
        granted = hasCore()
        if (prefs.setupDone && prefs.guardianOn) startGuardian()

        val activity = this
        setContent {
            DraupadiTheme {
                val alert by AppState.alert.collectAsState()
                val incoming by AppState.incoming.collectAsState()
                val listening by AppState.listening.collectAsState()
                val cloudStatus by AppState.cloudStatus.collectAsState()
                var settingsOpen by remember { mutableStateOf(false) }
                var setupDone by remember { mutableStateOf(prefs.setupDone) }
                var contacts by remember { mutableStateOf(prefs.contacts) }
                var word by remember { mutableStateOf(prefs.safeWord) }

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
                        onSafe = { GuardianService.send(activity, GuardianService.ACTION_SAFE) },
                        onCall = { callPolice() }
                    )

                    incoming.active -> IncomingScreen(
                        state = incoming,
                        onAccept = { GuardianService.send(activity, GuardianService.ACTION_ACCEPT) },
                        onDecline = { GuardianService.send(activity, GuardianService.ACTION_DISMISS) },
                        onNavigate = { openMaps(incoming.lat, incoming.lng) }
                    )

                    settingsOpen -> SettingsScreen(
                        prefs = prefs,
                        cloudStatus = cloudStatus,
                        onBack = { settingsOpen = false; word = prefs.safeWord },
                        onChanged = { restartGuardian() }
                    )

                    else -> HomeScreen(
                        safeWord = word,
                        listening = listening,
                        guardianOn = prefs.guardianOn,
                        onSos = {
                            if (!granted) askCore.launch(core)
                            else GuardianService.send(activity, GuardianService.ACTION_SOS, "button", prefs.silentOn)
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
    }

    private fun startGuardian() {
        if (!prefs.guardianOn) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, GuardianService::class.java))
        } catch (_: Throwable) {
        }
    }

    private fun restartGuardian() {
        try {
            if (prefs.guardianOn) {
                startGuardian()
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
