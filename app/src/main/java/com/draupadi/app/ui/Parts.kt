package com.draupadi.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The only control that matters. Hold, do not tap: a pocket cannot hold, and
 * a shaking hand can. The ring fills so there is never a doubt about whether
 * the phone registered the press.
 */
@Composable
fun HoldCircle(
    label: String,
    sub: String,
    tint: Color,
    diameter: Dp = 236.dp,
    holdMs: Int = 1200,
    onComplete: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    var fired by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(pressed) {
        if (pressed) {
            fired = false
            progress.snapTo(0f)
            progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
            if (!fired) {
                fired = true
                onComplete()
            }
        } else {
            progress.snapTo(0f)
        }
    }

    Box(
        modifier = Modifier
            .size(diameter)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(diameter)) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tint.copy(alpha = 0.95f), tint.copy(alpha = 0.55f)),
                    center = Offset(size.width * 0.36f, size.height * 0.3f),
                    radius = size.minDimension * 0.85f
                ),
                radius = size.minDimension / 2 - stroke
            )
            drawArc(
                color = Color.White.copy(alpha = 0.16f),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (progress.value > 0f) {
                drawArc(
                    color = Color.White,
                    startAngle = -90f, sweepAngle = 360f * progress.value, useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                color = Color.White,
                fontSize = 25.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(sub, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        }
    }
}

/** A wide hold-to-confirm bar, for anything that must not happen by accident. */
@Composable
fun HoldBar(
    label: String,
    tint: Color,
    holdMs: Int = 1400,
    modifier: Modifier = Modifier,
    onComplete: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    var fired by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(pressed) {
        if (pressed) {
            fired = false
            progress.snapTo(0f)
            progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
            if (!fired) {
                fired = true
                onComplete()
            }
        } else {
            progress.snapTo(0f)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(tint)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.value)
                .height(64.dp)
                .background(Color.White.copy(alpha = 0.28f))
        )
        Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** One line of live status. Deliberately terse. */
@Composable
fun StatusLine(done: Boolean, text: String, tint: Color = Safe) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(if (done) tint else Ink3)
        Spacer(Modifier.width(12.dp))
        Text(text, color = if (done) Ink else Ink2, fontSize = 15.sp)
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = Ink3,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        modifier = modifier
    )
}

@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, tint: Color = Ink2, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = tint, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SolidButton(text: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(tint)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun RowGap(w: Int) = Spacer(Modifier.width(w.dp))

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun SpacedColumn(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        content()
    }
}

/** A tap target for plain text, without dragging in the ripple machinery. */
fun Modifier.clickableText(onClick: () -> Unit): Modifier =
    this.then(Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) })

/** A live 0..1 meter. Used to prove the microphone and the accelerometer are
 *  actually alive, rather than asking anyone to take it on trust. */
@Composable
fun LevelBar(level: Float, tint: Color, height: Dp = 6.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(Color.White.copy(alpha = 0.08f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(tint)
        )
    }
}

/** A small three-way chooser. Used for shake sensitivity, where the right
 *  setting depends on the phone and can only really be found by trying. */
@Composable
fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) Red else Color.White.copy(alpha = 0.06f))
                    .pointerInput(label) { detectTapGestures(onTap = { onSelect(i) }) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (on) Color.White else Ink2,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}
