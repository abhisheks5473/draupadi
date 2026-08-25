package com.draupadi.app.ui

import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.draupadi.app.data.Contact
import com.draupadi.app.data.Prefs
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    prefs: Prefs,
    cloudStatus: String,
    micLevel: Float,
    shakeLevel: Float,
    shakeFlash: Boolean,
    heard: String,
    locationStatus: String,
    locationAlways: Boolean,
    locationSummary: String,
    locationAt: Long,
    locationOn: Boolean,
    onFixLocation: () -> Unit,
    onTestSos: () -> Unit,
    onBack: () -> Unit,
    onChanged: () -> Unit
) {
    var word by remember { mutableStateOf(prefs.safeWord) }
    var police by remember { mutableStateOf(prefs.policeNumber) }
    var name by remember { mutableStateOf(prefs.name) }
    var contacts by remember { mutableStateOf(prefs.contacts) }
    var guardian by remember { mutableStateOf(prefs.guardianOn) }
    var voice by remember { mutableStateOf(prefs.voiceOn) }
    var shake by remember { mutableStateOf(prefs.shakeOn) }
    var silent by remember { mutableStateOf(prefs.silentOn) }
    var respond by remember { mutableStateOf(prefs.respondOn) }
    var siren by remember { mutableStateOf(prefs.loudSiren) }
    var record by remember { mutableStateOf(prefs.autoRecord) }
    var sens by remember { mutableStateOf(prefs.shakeSensitivity) }
    val context = LocalContext.current

    // ticks so the "x seconds ago" below stays honest
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    prefs.addContact(Contact(c.getString(0) ?: "Contact", c.getString(1) ?: ""))
                    contacts = prefs.contacts
                }
            }
        } catch (_: Throwable) {
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .clickableText { onBack() },
                contentAlignment = Alignment.Center
            ) { Text("‹", color = Ink, fontSize = 22.sp) }
            RowGap(12)
            Text("Settings", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Gap(26)
        Label("you")
        Gap(10)
        Field(name, "Your name") { name = it; prefs.name = it }
        Gap(12)
        Field(word, "Safe word") { word = it; prefs.safeWord = it; onChanged() }
        Gap(12)
        Field(police, "Emergency number") { police = it; prefs.policeNumber = it }

        Gap(26)
        Label("who gets texted")
        Gap(10)
        contacts.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(c.number, color = Ink3, fontSize = 13.sp)
                }
                Text("Remove", color = Red, fontSize = 14.sp, modifier = Modifier.padding(8.dp).clickableText {
                    prefs.removeContact(c.number); contacts = prefs.contacts
                })
            }
        }
        Gap(8)
        SoftButton("Add someone") {
            picker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        }

        Gap(26)
        Label("how it triggers")
        Gap(6)
        Toggle("Guardian running", "The service that listens and can call for help", guardian) {
            guardian = it; prefs.guardianOn = it; onChanged()
        }
        Toggle("Listen for my safe word", "Uses on-device recognition where the phone supports it", voice) {
            voice = it; prefs.voiceOn = it; onChanged()
        }
        Toggle("Shake to trigger", "Shake the phone hard for about a second", shake) {
            shake = it; prefs.shakeOn = it; onChanged()
        }
        if (shake) {
            Gap(2)
            Choice(listOf("Firm shake", "Normal", "Light shake"), sens) {
                sens = it; prefs.shakeSensitivity = it; onChanged()
            }
            Gap(6)
            Text(
                "Firm is hardest to set off by accident. Try each one against the live bar below.",
                color = Ink3, fontSize = 12.5.sp, lineHeight = 18.sp
            )
            Gap(10)
        }
        Toggle("Silent alerts", "No siren, no bright screen. For a club or a cab", silent) {
            silent = it; prefs.silentOn = it
        }
        Toggle("Record on alert", "Camera and microphone start the instant an alert fires", record) {
            record = it; prefs.autoRecord = it
        }
        Toggle("Loud siren", "Sounds the alarm tone during an alert. Off by default — it also drowns out the recording", siren) {
            siren = it; prefs.loudSiren = it
        }
        Toggle("Answer other people's alerts", "Your phone buzzes when someone nearby is in trouble", respond) {
            respond = it; prefs.respondOn = it; onChanged()
        }

        Gap(26)
        Label("location")
        Gap(8)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Access", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    locationStatus,
                    color = if (locationAlways) Safe else Warn,
                    fontSize = 12.5.sp
                )
            }
            if (!locationAlways) {
                RowGap(12)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Red)
                        .clickableText { onFixLocation() }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Fix", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Gap(8)
        Text(
            if (locationAlways)
                "Your position is kept current in the background, so an alert never has to wait for a first GPS fix."
            else
                "Set location to “Allow all the time”. Android will not let the app share where you are once the screen is off otherwise — and that is exactly when it matters.",
            color = Ink3, fontSize = 12.5.sp, lineHeight = 18.sp
        )

        Gap(26)
        Label("check it works")
        Gap(4)
        Text(
            "Both triggers are live right now. Watch the bars move — nothing here sends anything.",
            color = Ink3, fontSize = 13.sp, lineHeight = 19.sp
        )

        Gap(16)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Microphone", color = Ink, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
            Text(if (voice && guardian) "listening" else "off", color = Ink3, fontSize = 12.5.sp)
        }
        Gap(6)
        LevelBar(micLevel, Safe)
        if (heard.isNotBlank()) {
            Gap(6)
            Text("heard: \u201C$heard\u201D", color = Ink3, fontSize = 12.sp, maxLines = 2)
        }

        Gap(18)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Shake", color = Ink, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
            Text(
                if (shakeFlash) "detected" else if (shake && guardian) "shake the phone" else "off",
                color = if (shakeFlash) Safe else Ink3,
                fontSize = 12.5.sp,
                fontWeight = if (shakeFlash) FontWeight.Bold else FontWeight.Normal
            )
        }
        Gap(6)
        LevelBar(shakeLevel, if (shakeFlash) Safe else Red)

        Gap(18)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Location", color = Ink, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
            Text(
                if (!locationOn) "phone location is off"
                else if (locationAt == 0L) "waiting for a fix" else "live",
                color = if (locationOn && locationAt > 0L) Safe else Warn,
                fontSize = 12.5.sp
            )
        }
        Gap(6)
        Text(
            if (locationSummary.isBlank()) "No position yet — step outside or switch location on."
            else "$locationSummary · ${((now - locationAt) / 1000).coerceAtLeast(0)} s ago",
            color = Ink3, fontSize = 12.5.sp
        )

        Gap(18)
        SoftButton("Run a test SOS (sends nothing)", tint = Ink) { onTestSos() }
        Gap(6)
        Text(
            "Records, shows the alert screen and saves to your Gallery — but no texts, no contacts, no police.",
            color = Ink3, fontSize = 12.5.sp, lineHeight = 18.sp
        )

        Gap(26)
        Label("network")
        Gap(8)
        Text(
            if (cloudStatus.isBlank()) "Checking…" else cloudStatus,
            color = if (cloudStatus.startsWith("Connected")) Safe else Warn,
            fontSize = 14.sp
        )
        Gap(6)
        Text(
            "Without a connected project, texts, recording and the gallery save all still work. Only the neighbourhood broadcast needs the network.",
            color = Ink3, fontSize = 13.sp, lineHeight = 19.sp
        )
        Gap(50)
    }
}

@Composable
private fun Toggle(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Ink3, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        RowGap(12)
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Red,
                uncheckedThumbColor = Ink3,
                uncheckedTrackColor = Color.White.copy(alpha = 0.10f)
            )
        )
    }
}
