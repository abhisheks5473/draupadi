package com.draupadi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.draupadi.app.core.ResultUi

/**
 * What just happened, in plain words. Shown once the alert closes, because
 * "did that work?" is the question everyone asks the moment it is over.
 */
@Composable
fun ResultScreen(state: ResultUi, onDone: () -> Unit) {
    val mm = state.seconds / 60
    val ss = state.seconds % 60

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Gap(90)
        Text(
            when {
                state.dryRun -> "Test finished"
                state.cancelled -> "Alert cancelled"
                else -> "You are marked safe"
            },
            color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
        Gap(10)
        Text(
            when {
                state.dryRun -> "Everything ran except the messages. Nobody was contacted."
                state.cancelled -> "Everyone you alerted has been told it was a false alarm."
                else -> "Your contacts have been told you are safe."
            },
            color = Ink2, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center
        )

        Gap(30)
        Column(Modifier.fillMaxWidth()) {
            Line("Alert lasted", String.format("%02d:%02d", mm, ss))
            if (!state.dryRun) {
                Line("Texts delivered", "${state.smsSent} of ${state.smsTotal}")
                if (state.reached > 0) Line("Phones alerted nearby", "${state.reached}")
            }
            Line(
                "Recording",
                if (state.savedVideo != null) "Saved to Gallery" else "None"
            )
        }

        Gap(34)
        SolidButton("Done", tint = Red, onClick = onDone)
    }
}

@Composable
private fun Line(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Text(label, color = Ink3, fontSize = 12.5.sp)
        Text(value, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
