package com.sudomastery.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.sudomastery.tvremote.AdvancedState
import com.sudomastery.tvremote.TvAction

/**
 * App management that the remote protocol can't do, sent over network ADB: close the app on
 * screen, clear out everything else to free memory, or restart the TV.
 */
@Composable
fun AdvancedPanel(state: AdvancedState, enabled: Boolean, onAction: (TvAction) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHaptic.current
    var confirmRestart by remember { mutableStateOf(false) }
    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(Palette.surface)
            .border(1.5.dp, Palette.accent, RoundedCornerShape(32.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MemoryBar(state)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(Icons.Rounded.Close, "Close this app", TvAction.CLOSE_CURRENT, state, enabled, Modifier.weight(1f)) { haptic(); onAction(it) }
            ActionTile(Icons.Rounded.CleaningServices, "Close other apps", TvAction.CLOSE_OTHERS, state, enabled, Modifier.weight(1f)) { haptic(); onAction(it) }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(Icons.Rounded.Layers, "Close all apps", TvAction.CLOSE_ALL, state, enabled, Modifier.weight(1f)) { haptic(); onAction(it) }
            ActionTile(Icons.Rounded.RestartAlt, "Restart TV", TvAction.RESTART, state, enabled, Modifier.weight(1f)) { haptic(); confirmRestart = true }
        }
        Text(
            state.message ?: if (enabled) "Close other apps before starting something heavy to free RAM" else "Needs Wi-Fi mode and a paired TV",
            color = if (state.error) Palette.amber else Palette.muted,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
    }
    if (confirmRestart) {
        ConfirmDialog(
            title = "Restart the TV?",
            body = "Everything on the TV closes and it reboots. It takes about a minute to come back.",
            confirm = "Restart",
            onConfirm = { confirmRestart = false; onAction(TvAction.RESTART) },
            onDismiss = { confirmRestart = false },
        )
    }
}

@Composable
private fun MemoryBar(state: AdvancedState) {
    val memory = state.memory
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TV memory", color = Palette.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                if (memory != null) "${memory.availableKb / 1024} MB free of ${memory.totalKb / 1024}" else "not read yet",
                color = Palette.muted,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (memory != null && memory.totalKb > 0) 1f - memory.availableKb.toFloat() / memory.totalKb else 0f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
            color = Palette.violet,
            trackColor = Palette.pressed,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    title: String,
    action: TvAction,
    state: AdvancedState,
    enabled: Boolean,
    modifier: Modifier,
    onClick: (TvAction) -> Unit,
) {
    val busy = state.busy == action
    val usable = enabled && state.busy == null
    Column(
        modifier
            .fillMaxHeight()
            .alpha(if (enabled && (state.busy == null || busy)) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.surfaceHigh)
            .then(if (usable) Modifier.clickable { onClick(action) } else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Palette.violet, strokeWidth = 2.dp)
            } else {
                Icon(icon, null, tint = if (action == TvAction.RESTART) Palette.red else Palette.onSurface, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, color = Palette.onSurface, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalContentColor provides Palette.onSurface) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Palette.surface).padding(24.dp),
            ) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(body, color = Palette.muted, fontSize = 15.sp)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.muted) }
                    Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Palette.red, contentColor = Color.White)) { Text(confirm) }
                }
            }
        }
    }
}
