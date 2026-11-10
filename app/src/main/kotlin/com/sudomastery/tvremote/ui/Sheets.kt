package com.sudomastery.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Input
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudomastery.tvremote.RemoteKey
import com.sudomastery.tvremote.data.FoundTv
import com.sudomastery.tvremote.data.SavedTv
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.surface,
        contentColor = Palette.onSurface,
        scrimColor = Palette.background.copy(alpha = 0.6f),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding().imePadding()) {
            content()
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 16.dp))
}

/**
 * Types into the focused field on the TV as you type here. Characters are sent as they are added,
 * deletions become backspace presses.
 */
@Composable
fun KeyboardSheet(onText: (String) -> Unit, onKey: (RemoteKey) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    RemoteSheet(onDismiss) {
        SheetTitle("Type on TV")
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                when {
                    next.startsWith(value) -> next.substring(value.length).takeIf { it.isNotEmpty() }?.let(onText)
                    value.startsWith(next) -> repeat(value.length - next.length) { onKey(RemoteKey.DELETE) }
                    else -> {
                        // Edited in the middle: replace the whole field.
                        repeat(value.length) { onKey(RemoteKey.DELETE) }
                        if (next.isNotEmpty()) onText(next)
                    }
                }
                value = next
            },
            placeholder = { Text("Start typing…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onKey(RemoteKey.ENTER) }),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.violet,
                unfocusedBorderColor = Palette.pressed,
                cursorColor = Palette.violet,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundKey(Icons.AutoMirrored.Rounded.Backspace, "Backspace", size = 56.dp, background = Palette.surfaceHigh, repeat = true) {
                if (value.isNotEmpty()) value = value.dropLast(1)
                onKey(RemoteKey.DELETE)
            }
            RoundKey(Icons.AutoMirrored.Rounded.KeyboardReturn, "Enter", size = 56.dp, background = Palette.surfaceHigh) { onKey(RemoteKey.ENTER) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { value = "" }) { Text("Clear box", color = Palette.muted) }
        }
    }
}

/** Number pad plus the keys that don't fit on the main screen. */
@Composable
fun MoreKeysSheet(onKey: (RemoteKey) -> Unit, onPowerMenu: () -> Unit, onDismiss: () -> Unit) {
    RemoteSheet(onDismiss) {
        SheetTitle("More keys")
        val digits = RemoteKey.digits
        val grid = listOf(digits.subList(0, 3), digits.subList(3, 6), digits.subList(6, 9))
        grid.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { DigitKey(it, onKey) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundKey(Icons.Rounded.ClosedCaption, "Captions", background = Palette.surfaceHigh) { onKey(RemoteKey.CAPTIONS) }
            DigitKey(RemoteKey.DIGIT_0, onKey)
            RoundKey(Icons.Rounded.Input, "Input", background = Palette.surfaceHigh) { onKey(RemoteKey.INPUT) }
        }
        HorizontalDivider(color = Palette.pressed)
        Spacer(Modifier.height(18.dp))
        KeyRow(
            listOf(
                Triple(Icons.Rounded.SkipPrevious, "Previous", RemoteKey.PREVIOUS),
                Triple(Icons.Rounded.FastRewind, "Rewind", RemoteKey.REWIND),
                Triple(Icons.Rounded.PlayArrow, "Play or pause", RemoteKey.PLAY_PAUSE),
                Triple(Icons.Rounded.FastForward, "Fast forward", RemoteKey.FAST_FORWARD),
                Triple(Icons.Rounded.SkipNext, "Next", RemoteKey.NEXT),
            ),
            onKey,
        )
        Spacer(Modifier.height(12.dp))
        KeyRow(
            listOf(
                Triple(Icons.Rounded.Stop, "Stop", RemoteKey.STOP),
                Triple(Icons.Rounded.Menu, "Menu", RemoteKey.MENU),
                Triple(Icons.Rounded.LiveTv, "Guide", RemoteKey.GUIDE),
                Triple(Icons.Rounded.Info, "Info", RemoteKey.INFO),
                Triple(Icons.Rounded.Settings, "TV settings", RemoteKey.SETTINGS),
            ),
            onKey,
        )
        Spacer(Modifier.height(18.dp))
        OutlinedButton(
            onClick = onPowerMenu,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Palette.pressed),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.onSurface),
        ) {
            Icon(Icons.Rounded.PowerSettingsNew, null, tint = Palette.red)
            Spacer(Modifier.width(10.dp))
            Text("Shut down TV…", fontSize = 16.sp)
        }
        Text(
            "Opens the TV's power off dialog. Same as holding the power button.",
            color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun KeyRow(keys: List<Triple<ImageVector, String, RemoteKey>>, onKey: (RemoteKey) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        keys.forEach { (icon, label, key) ->
            RoundKey(icon, label, size = 56.dp, background = Palette.surfaceHigh, repeat = key == RemoteKey.REWIND || key == RemoteKey.FAST_FORWARD) { onKey(key) }
        }
    }
}

@Composable
private fun DigitKey(key: RemoteKey, onKey: (RemoteKey) -> Unit) {
    val haptic = LocalHaptic.current
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(if (pressed) Palette.pressed else Palette.surfaceHigh)
            .remotePress(onPressedChange = { pressed = it }) { haptic(); onKey(key) },
        contentAlignment = Alignment.Center,
    ) {
        Text(key.name.removePrefix("DIGIT_"), fontSize = 24.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun DevicesSheet(
    saved: List<SavedTv>,
    current: SavedTv?,
    scan: () -> Flow<List<FoundTv>>,
    onSelect: (SavedTv) -> Unit,
    onForget: (SavedTv) -> Unit,
    onPair: (FoundTv) -> Unit,
    onDismiss: () -> Unit,
) {
    val found by remember { scan() }.collectAsState(initial = emptyList())
    var manualIp by remember { mutableStateOf("") }
    RemoteSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (saved.isNotEmpty()) {
                SheetTitle("Your TVs")
                saved.forEach { tv ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSelect(tv) }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Tv, null, tint = if (tv.host == current?.host) Palette.violet else Palette.muted)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tv.name, fontSize = 16.sp)
                            Text(tv.host, fontSize = 13.sp, color = Palette.muted)
                        }
                        if (tv.host == current?.host) Icon(Icons.Rounded.Check, "Selected", tint = Palette.violet)
                        IconButton(onClick = { onForget(tv) }) { Icon(Icons.Rounded.DeleteOutline, "Forget ${tv.name}", tint = Palette.muted) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("On this Wi-Fi", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                CircularProgressIndicator(Modifier.size(18.dp), color = Palette.violet, strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(8.dp))
            val newOnes = found.filter { f -> saved.none { it.host == f.host && it.pinSha256 != null } }
            if (newOnes.isEmpty()) {
                Text(
                    if (found.isEmpty()) "Looking for TVs… Make sure the TV is on and connected to the same network." else "No new TVs found.",
                    color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            newOnes.forEach { tv ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onPair(tv) }
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Palette.muted)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tv.name, fontSize = 16.sp)
                        Text(tv.host, fontSize = 13.sp, color = Palette.muted)
                    }
                    Text("Pair", color = Palette.violet, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Or enter the TV's IP address", color = Palette.muted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it.filter { c -> c.isDigit() || c == '.' } },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (manualIp.isValidIp()) onPair(FoundTv(manualIp, manualIp)) }),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.violet, unfocusedBorderColor = Palette.pressed, cursorColor = Palette.violet),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { onPair(FoundTv(manualIp, manualIp)) },
                    enabled = manualIp.isValidIp(),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.violet),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Pair") }
            }
        }
    }
}

private fun String.isValidIp(): Boolean {
    val parts = split('.')
    return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
}

@Composable
fun SettingsSheet(
    haptics: Boolean,
    onHaptics: (Boolean) -> Unit,
    canRepair: Boolean,
    onRepair: () -> Unit,
    onManageTvs: () -> Unit,
    extra: @Composable () -> Unit = {},
    onDismiss: () -> Unit,
) {
    RemoteSheet(onDismiss) {
        SheetTitle("Settings")
        SettingRow("Vibrate on press", "A light tick for every key") {
            Switch(haptics, onHaptics, colors = SwitchDefaults.colors(checkedTrackColor = Palette.violet))
        }
        extra()
        SettingRow("Manage TVs", "Add, switch or forget TVs", onClick = onManageTvs)
        if (canRepair) SettingRow("Pair this TV again", "Use if the TV was reset or stopped responding", onClick = onRepair)
        Spacer(Modifier.height(12.dp))
        Text(
            "Works over Wi-Fi with Google TV and Android TV, using the same protocol as Google's own remote.",
            color = Palette.muted, fontSize = 13.sp,
        )
    }
}

@Composable
fun SettingRow(title: String, subtitle: String, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(subtitle, fontSize = 13.sp, color = Palette.muted)
        }
        trailing()
    }
}
