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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.draupadi.app.core.Geo
import com.draupadi.app.core.IncomingUi

/**
 * The other side of the alert: your phone buzzing morse SOS because someone
 * a few streets away is in trouble.
 *
 * Before enough people accept, all you get is a neighbourhood. The exact spot
 * is released by the server only once three verified people have committed —
 * so one stranger tapping "accept" can never pull a woman's location out of
 * this app.
 */
@Composable
fun IncomingScreen(
    state: IncomingUi,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onNavigate: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(RedDeep.copy(alpha = 0.5f), Bg, Bg)))
            .padding(horizontal = 24.dp)
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Label("someone needs help")
                Gap(16)
                Text(
                    Geo.pretty(state.distanceM.toDouble()),
                    color = Ink, fontSize = 56.sp, fontWeight = FontWeight.Black
                )
                Gap(4)
                Text("away from you", color = Ink2, fontSize = 16.sp)
                Gap(24)
                Text(
                    if (state.accepted && !state.unlocked)
                        "Waiting for ${3 - state.acceptedCount.coerceAtMost(3)} more people. " +
                            "Her exact location unlocks when three of us have said yes."
                    else if (state.unlocked)
                        "Her exact location is live. Go carefully, and call the police on your way."
                    else
                        "Only accept if you can actually go. You will get her exact location once three of us have.",
                    color = Ink2, fontSize = 15.sp, lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }

        if (state.closed) {
            Text(
                "She has marked herself safe.",
                color = Safe, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                textAlign = TextAlign.Center
            )
            SolidButton("Close", tint = Safe, onClick = onDecline)
        } else if (!state.accepted) {
            SolidButton("I can help", tint = Red, onClick = onAccept)
            Gap(10)
            SoftButton("Can’t right now", onClick = onDecline)
        } else {
            if (state.unlocked) {
                SolidButton("Open in Maps", tint = Red, onClick = onNavigate)
                Gap(10)
            }
            SoftButton("Stand down", onClick = onDecline)
        }
        Gap(34)
    }
}
