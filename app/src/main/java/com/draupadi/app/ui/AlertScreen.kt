package com.draupadi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.draupadi.app.core.AlertUi

/**
 * While an alert is live the screen answers exactly one question — is help
 * actually coming? — and offers exactly one action.
 */
@Composable
fun AlertScreen(
    state: AlertUi,
    cloudOn: Boolean,
    policeNumber: String,
    onSafe: () -> Unit,
    onCall: () -> Unit
) {
    val mm = state.seconds / 60
    val ss = state.seconds % 60

    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(RedDeep.copy(alpha = 0.55f), Bg, Bg)
                )
            )
            .padding(horizontal = 24.dp)
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Label("help is being called")
                Gap(10)
                Text(
                    String.format("%02d:%02d", mm, ss),
                    color = Ink, fontSize = 62.sp, fontWeight = FontWeight.Black
                )
            }
        }

        Gap(30)

        Column(Modifier.weight(1f)) {
            StatusLine(
                done = state.smsSent > 0,
                text = if (state.smsSent > 0) "Texted ${state.smsSent} people your location"
                else "Sending texts…"
            )
            StatusLine(
                done = state.recording || state.savedVideo != null,
                text = when {
                    state.savedVideo != null -> "Recording saved to your Gallery"
                    state.recording -> "Recording video and sound"
                    else -> "Starting the camera…"
                },
                tint = Red
            )
            if (cloudOn) {
                StatusLine(
                    done = state.reached > 0,
                    text = if (state.reached > 0)
                        "${state.reached} phones buzzing within ${state.radiusKm} km"
                    else "Reaching phones near you…"
                )
                StatusLine(
                    done = state.unlocked,
                    text = if (state.unlocked)
                        "${state.accepted} people are coming — they can see you"
                    else "${state.accepted} of 3 accepted"
                )
            }
            if (state.locationFixed) {
                StatusLine(done = true, text = "Live location is being shared")
            }
        }

        SoftButton("Call $policeNumber", tint = Ink, onClick = onCall)
        Gap(12)
        HoldBar("HOLD — I am safe", tint = Safe, onComplete = onSafe)
        Gap(34)
    }
}
