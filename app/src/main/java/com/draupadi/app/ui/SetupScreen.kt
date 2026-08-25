package com.draupadi.app.ui

import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.draupadi.app.data.Contact

/**
 * Three questions, one screen each. Nobody sets up a safety app carefully;
 * they set it up once, in a hurry, and never open it again.
 */
@Composable
fun SetupScreen(
    initialName: String,
    initialWord: String,
    contacts: List<Contact>,
    onAddContact: (Contact) -> Unit,
    onRemoveContact: (String) -> Unit,
    onAskPermissions: () -> Unit,
    onBatterySettings: () -> Unit,
    permissionsGranted: Boolean,
    onDone: (name: String, word: String) -> Unit
) {
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf(initialName) }
    var word by remember { mutableStateOf(initialWord) }
    val context = LocalContext.current

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
                    onAddContact(Contact(c.getString(0) ?: "Contact", c.getString(1) ?: ""))
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
        Gap(30)
        Label("step ${step + 1} of 3")
        Gap(12)

        when (step) {
            0 -> {
                BigTitle("Your name and\nyour safe word")
                Gap(12)
                Text(
                    "Say the safe word out loud and Draupadi starts an alert — phone in your bag, screen off, hands free.",
                    color = Ink2, fontSize = 15.sp, lineHeight = 22.sp
                )
                Gap(22)
                Field(name, "Your name", { name = it })
                Gap(12)
                Field(word, "Safe word", { word = it })
                Gap(10)
                Text(
                    "Pick something you would never say by accident.",
                    color = Ink3, fontSize = 13.sp
                )
                Gap(26)
                SolidButton("Next", tint = Red) { if (name.isNotBlank() && word.isNotBlank()) step = 1 }
            }

            1 -> {
                BigTitle("Who should be\ntexted instantly")
                Gap(12)
                Text(
                    "The moment an alert starts they get a text with a live map link. No confirmation, no tap — you will not be in a state to give one.",
                    color = Ink2, fontSize = 15.sp, lineHeight = 22.sp
                )
                Gap(20)
                contacts.forEach { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(c.number, color = Ink3, fontSize = 13.sp)
                        }
                        Text(
                            "Remove",
                            color = Red,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(8.dp).clickableText { onRemoveContact(c.number) }
                        )
                    }
                }
                Gap(10)
                SoftButton("Add someone from my contacts") {
                    picker.launch(
                        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                    )
                }
                Gap(10)
                Text("112 is always texted too.", color = Ink3, fontSize = 13.sp)
                Gap(26)
                SolidButton("Next", tint = Red) { step = 2 }
            }

            else -> {
                BigTitle("Let Draupadi\ndo its job")
                Gap(12)
                Text(
                    "It needs the microphone to hear your word, the camera to record what happens, your location to send help, and permission to text — all granted once, now, so nothing is ever asked of you mid-emergency.\n\nWhen Android asks about location, choose \u201CAllow all the time\u201D. Anything less and it stops sharing where you are the moment the screen goes off.",
                    color = Ink2, fontSize = 15.sp, lineHeight = 22.sp
                )
                Gap(24)
                SolidButton(
                    if (permissionsGranted) "Permissions granted" else "Allow everything",
                    tint = if (permissionsGranted) Safe else Red
                ) { onAskPermissions() }
                Gap(10)
                SoftButton("Stop Android from killing it") { onBatterySettings() }
                Gap(8)
                Text(
                    "Find Draupadi in that list and set it to Unrestricted. Without this Android quietly stops it listening after a few hours.",
                    color = Ink3, fontSize = 13.sp, lineHeight = 19.sp
                )
                Gap(26)
                SolidButton("Finish", tint = Red) { onDone(name, word) }
            }
        }
        Gap(40)
    }
}

@Composable
fun Field(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = Ink3) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White.copy(alpha = 0.05f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
            focusedIndicatorColor = Red,
            unfocusedIndicatorColor = Color.White.copy(alpha = 0.15f),
            focusedTextColor = Ink,
            unfocusedTextColor = Ink,
            cursorColor = Red
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
