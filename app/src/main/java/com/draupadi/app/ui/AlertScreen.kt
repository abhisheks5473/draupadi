package com.draupadi.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.draupadi.app.core.AlertUi

/**
 * While an alert is live the screen answers one question — did it actually go
 * out, and is help coming? — and offers one action.
 *
 * The counts are real: they come from the SMS layer's delivery callbacks, not
 * from optimism.
 */
@Composable
fun AlertScreen(
    state: AlertUi,
    cloudOn: Boolean,
    policeNumber: String,
    locationSummary: String,
    onSafe: () -> Unit,
    onCancel: () -> Unit,
    onCall: () -> Unit
) {
    val mm = state.seconds / 60
    val ss = state.seconds % 60
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(RedDeep.copy(alpha = 0.6f), Bg, Bg)))
            .padding(horizontal = 22.dp)
    ) {
        // ---- a way back out, for the first few seconds only
        if (state.cancelSecs > 0 && !state.dryRun) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Warn.copy(alpha = 0.18f))
                    .clickableText { onCancel() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Not an emergency?  CANCEL  (${state.cancelSecs})",
                    color = Warn, fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        // ---- the receipt
        Column(
            Modifier.fillMaxWidth().padding(top = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.alpha(pulse)) { Dot(Red, 12.dp) }
                RowGap(10)
                Text(
                    if (state.dryRun) "TEST RUNNING" else "SOS SENT",
                    color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }
            Gap(6)
            Text(
                if (state.dryRun) "Nothing was sent to anyone"
                else "Help has been called. Stay on this screen.",
                color = Ink2, fontSize = 14.sp, textAlign = TextAlign.Center
            )
            Gap(14)
            Text(
                String.format("%02d:%02d", mm, ss),
                color = Ink, fontSize = 54.sp, fontWeight = FontWeight.Black
            )
        }

        Gap(22)

        Column(Modifier.weight(1f)) {
            if (!state.dryRun) {
                StatusLine(
                    done = state.smsSent > 0,
                    text = when {
                        state.smsTotal == 0 -> "No contacts saved — add them in settings"
                        state.smsSent > 0 -> "Texted ${state.smsSent} of ${state.smsTotal} · location included"
                        state.smsFailed > 0 -> "${state.smsFailed} texts failed — check your SIM"
                        else -> "Sending texts to ${state.smsTotal} people…"
                    },
                    tint = if (state.smsFailed > 0 && state.smsSent == 0) Warn else Safe
                )
            }
            StatusLine(
                done = state.recording || state.savedVideo != null,
                text = when {
                    state.savedVideo != null -> "Recording saved to your Gallery"
                    state.recording -> "Recording video and sound"
                    else -> "Starting the camera…"
                },
                tint = Red
            )
            StatusLine(
                done = state.locationFixed,
                text = if (state.locationFixed)
                    "Location shared" + (if (locationSummary.isNotBlank()) " · $locationSummary" else "")
                else "Getting a fix — an updated link follows automatically"
            )
            if (cloudOn && !state.dryRun) {
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
        }

        if (!state.dryRun) {
            SoftButton("Call $policeNumber", tint = Ink, onClick = onCall)
            Gap(10)
        }
        HoldBar(
            if (state.dryRun) "HOLD — end the test" else "HOLD — I am safe",
            tint = Safe,
            onComplete = onSafe
        )
        Gap(28)
        Box(Modifier.height(0.dp))
    }
}
