package com.sudomastery.tvremote.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudomastery.tvremote.AppShortcut

@Composable
fun AppRow(onLaunch: (AppShortcut) -> Unit, modifier: Modifier = Modifier, isEnabled: (AppShortcut) -> Boolean = { true }) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppShortcut.entries.forEach { app ->
            AppTile(app, isEnabled(app), onClick = { onLaunch(app) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun AppTile(app: AppShortcut, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val haptic = LocalHaptic.current
    val action by rememberUpdatedState(onClick)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, tween(90), label = "tile")
    Column(modifier.alpha(if (enabled) 1f else DISABLED_ALPHA), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .scale(scale)
                .clip(RoundedCornerShape(18.dp))
                .then(if (enabled) Modifier.remotePress(onPressedChange = { pressed = it }) { haptic(); action() } else Modifier),
        ) { AppGlyph(app) }
        Spacer(Modifier.height(6.dp))
        Text(app.label, color = Palette.onSurface, fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** Simple brand-coloured glyphs drawn in code, so the APK carries no image assets. */
@Composable
private fun AppGlyph(app: AppShortcut) {
    when (app) {
        AppShortcut.NETFLIX -> Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0B)), Alignment.Center) {
            Text("N", color = Color(0xFFE50914), fontSize = 40.sp, fontWeight = FontWeight.Black)
        }

        AppShortcut.TIZENTUBE -> Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A)), Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width * 0.62f
                val h = w * 0.7f
                val left = (size.width - w) / 2
                val top = (size.height - h) / 2
                drawRoundRect(Color(0xFFFF0033), Offset(left, top), Size(w, h), androidx.compose.ui.geometry.CornerRadius(h * 0.28f))
                val t = h * 0.42f
                val cx = size.width / 2 + t * 0.12f
                val cy = size.height / 2
                drawPath(Path().apply {
                    moveTo(cx - t * 0.45f, cy - t / 2)
                    lineTo(cx + t * 0.55f, cy)
                    lineTo(cx - t * 0.45f, cy + t / 2)
                    close()
                }, Color.White)
            }
        }

        AppShortcut.PRIME -> Box(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1A98FF), Color(0xFF0F6FD1)))),
            Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("prime", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 17.sp)
                Text("video", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 13.sp)
            }
        }

        AppShortcut.SPOTIFY -> Box(Modifier.fillMaxSize().background(Color(0xFF121212)), Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension * 0.3f
                drawCircle(Color(0xFF1ED760), r)
                val stroke = r * 0.14f
                listOf(0.62f to -0.32f, 0.5f to 0.02f, 0.38f to 0.32f).forEach { (width, dy) ->
                    val arcW = r * 2 * width * 1.25f
                    val arcH = arcW * 0.45f
                    drawArc(
                        Color(0xFF121212), startAngle = 200f, sweepAngle = 140f, useCenter = false,
                        topLeft = Offset(center.x - arcW / 2, center.y + dy * r - arcH * 0.1f),
                        size = Size(arcW, arcH),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}
