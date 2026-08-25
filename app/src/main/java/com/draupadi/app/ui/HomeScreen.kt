package com.draupadi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Home is one button and one sentence.
 *
 * Everything else — contacts, the safe word, the toggles — lives behind the
 * dot in the corner. Under real fear, choice is the enemy.
 */
@Composable
fun HomeScreen(
    safeWord: String,
    listening: Boolean,
    guardianOn: Boolean,
    onSos: () -> Unit,
    onSettings: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 24.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Dot(
                when {
                    !guardianOn -> Ink3
                    listening -> Safe
                    else -> Warn
                }
            )
            RowGap(10)
            Text(
                when {
                    !guardianOn -> "Guardian is off"
                    listening -> "Listening for “$safeWord”"
                    else -> "Guardian on"
                },
                color = Ink2,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { onSettings() }) },
                contentAlignment = Alignment.Center
            ) {
                Text("•••", color = Ink2, fontSize = 15.sp)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HoldCircle(
                    label = "HOLD",
                    sub = "for help",
                    tint = Red,
                    onComplete = onSos
                )
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "or say your safe word out loud",
                color = Ink3, fontSize = 14.sp, textAlign = TextAlign.Center
            )
            Text(
                "or shake the phone hard",
                color = Ink3, fontSize = 14.sp, textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.size(0.dp))
    }
}

@Composable
fun BigTitle(text: String) {
    Text(text, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp)
}
