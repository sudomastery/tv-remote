package com.sudomastery.tvremote.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sudomastery.atv.ConnectionStatus
import com.sudomastery.atv.proto.RemoteProto.RemoteDirection
import com.sudomastery.tvremote.RemoteKey
import com.sudomastery.tvremote.RemoteViewModel

private enum class Sheet { NONE, KEYBOARD, MORE, DEVICES, SETTINGS }

@Composable
fun RemoteScreen(vm: RemoteViewModel) {
    val tvs by vm.tvs.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val tv by vm.tvState.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val haptics by vm.haptics.collectAsStateWithLifecycle()
    val listening by vm.listening.collectAsStateWithLifecycle()

    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    var touchpad by rememberSaveable { mutableStateOf(false) }

    // First run: go straight to finding a TV.
    LaunchedEffect(tvs.isEmpty()) { if (tvs.isEmpty()) sheet = Sheet.DEVICES }

    val press: (RemoteKey) -> Unit = { vm.press(it) }

    RemoteTheme(haptics) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.background)
                .safeDrawingPadding()
                .padding(horizontal = 20.dp),
        ) {
            Header(
                name = current?.name ?: "No TV",
                status = tv.status,
                isOn = tv.isOn,
                onSettings = { sheet = Sheet.SETTINGS },
                onDevices = { sheet = Sheet.DEVICES },
                onPower = { press(RemoteKey.POWER) },
            )
            StatusBanner(
                hasTv = current != null,
                status = tv.status,
                onFind = { sheet = Sheet.DEVICES },
                onPair = vm::repairCurrent,
            )
            Spacer(Modifier.height(12.dp))
            AppRow(onLaunch = { vm.launch(it) })

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth * 0.88f, maxHeight - 40.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Crossfade(touchpad, label = "pad") { pad ->
                        val onKey: (PadKey) -> Unit = { k -> press(k.toRemoteKey()) }
                        if (pad) {
                            Touchpad(onKey, Modifier.size(side))
                        } else {
                            DPad(
                                onKey = onKey,
                                onOkLongStart = { vm.press(RemoteKey.OK, RemoteDirection.START_LONG) },
                                onOkLongEnd = { vm.press(RemoteKey.OK, RemoteDirection.END_LONG) },
                                modifier = Modifier.size(side),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    PadSwitch(touchpad) { touchpad = it }
                }
            }

            BottomControls(
                volume = tv.volume?.level,
                muted = tv.volume?.muted == true,
                listening = listening,
                onKey = press,
                onKeyboard = { sheet = Sheet.KEYBOARD },
                onMore = { sheet = Sheet.MORE },
                onVoiceStart = vm::startVoice,
                onVoiceStop = vm::stopVoice,
            )
            Spacer(Modifier.height(12.dp))
        }

        when (sheet) {
            Sheet.KEYBOARD -> KeyboardSheet(onText = { vm.sendText(it) }, onKey = press, onDismiss = { sheet = Sheet.NONE })
            Sheet.MORE -> MoreKeysSheet(onKey = press, onDismiss = { sheet = Sheet.NONE })
            Sheet.DEVICES -> DevicesSheet(
                saved = tvs,
                current = current,
                scan = vm::scan,
                onSelect = { vm.select(it); sheet = Sheet.NONE },
                onForget = vm::forget,
                onPair = { vm.choose(it); sheet = Sheet.NONE },
                onDismiss = { sheet = Sheet.NONE },
            )
            Sheet.SETTINGS -> SettingsSheet(
                haptics = haptics,
                onHaptics = vm::setHaptics,
                canRepair = current != null,
                onRepair = { vm.repairCurrent(); sheet = Sheet.NONE },
                onManageTvs = { sheet = Sheet.DEVICES },
                onDismiss = { sheet = Sheet.NONE },
            )
            Sheet.NONE -> Unit
        }

        PairingDialog(
            state = pairing,
            onSubmit = vm::submitCode,
            onRetry = { (pairing as? com.sudomastery.tvremote.PairingState.Failed)?.let { vm.startPairing(it.tv) } },
            onCancel = vm::cancelPairing,
        )
    }
}

private fun PadKey.toRemoteKey() = when (this) {
    PadKey.UP -> RemoteKey.UP
    PadKey.DOWN -> RemoteKey.DOWN
    PadKey.LEFT -> RemoteKey.LEFT
    PadKey.RIGHT -> RemoteKey.RIGHT
    PadKey.OK -> RemoteKey.OK
}

@Composable
private fun Header(
    name: String,
    status: ConnectionStatus,
    isOn: Boolean?,
    onSettings: () -> Unit,
    onDevices: () -> Unit,
    onPower: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = Palette.onSurface, modifier = Modifier.size(28.dp)) }
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onDevices)
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(status.color()))
                Spacer(Modifier.width(8.dp))
                Text(name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.onSurface)
                Icon(Icons.Rounded.ArrowDropDown, "Choose TV", tint = Palette.onSurface)
            }
            Text(status.label(), fontSize = 12.sp, color = Palette.muted)
        }
        RoundKey(
            Icons.Rounded.PowerSettingsNew, "Power",
            size = 48.dp,
            tint = if (isOn == true) Palette.green else Palette.red,
            background = Color.Transparent,
            onPress = onPower,
        )
    }
}

private fun ConnectionStatus.color() = when (this) {
    ConnectionStatus.Connected -> Palette.green
    ConnectionStatus.Connecting -> Palette.amber
    ConnectionStatus.NeedsPairing, ConnectionStatus.CertificateChanged -> Palette.blue
    ConnectionStatus.Disconnected -> Palette.muted
    is ConnectionStatus.Unreachable -> Palette.red
}

private fun ConnectionStatus.label() = when (this) {
    ConnectionStatus.Connected -> "Connected"
    ConnectionStatus.Connecting -> "Connecting…"
    ConnectionStatus.NeedsPairing -> "Needs pairing"
    ConnectionStatus.CertificateChanged -> "Pair again"
    ConnectionStatus.Disconnected -> "Not connected"
    is ConnectionStatus.Unreachable -> "Can't reach TV"
}

@Composable
private fun StatusBanner(hasTv: Boolean, status: ConnectionStatus, onFind: () -> Unit, onPair: () -> Unit) {
    val content: Pair<String, Pair<String, () -> Unit>>? = when {
        !hasTv -> "No TV set up yet." to ("Find TV" to onFind)
        status == ConnectionStatus.NeedsPairing -> "The TV doesn't recognise this phone." to ("Pair" to onPair)
        status == ConnectionStatus.CertificateChanged -> "The TV's identity changed, so it needs to be paired again." to ("Pair" to onPair)
        status is ConnectionStatus.Unreachable -> "Can't reach the TV. Is it on and on this Wi-Fi?" to ("TVs" to onFind)
        else -> null
    }
    AnimatedVisibility(content != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val (text, action) = content ?: return@AnimatedVisibility
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.surface)
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, fontSize = 14.sp, color = Palette.onSurface, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = action.second) {
                Text(action.first, color = Palette.violet, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PadSwitch(touchpad: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Palette.surface).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(false to "D-pad", true to "Touchpad").forEach { (value, label) ->
            val selected = value == touchpad
            Text(
                label,
                fontSize = 13.sp,
                color = if (selected) Palette.onSurface else Palette.muted,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Palette.surfaceHigh else Color.Transparent)
                    .clickable { onChange(value) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun BottomControls(
    volume: Int?,
    muted: Boolean,
    listening: Boolean,
    onKey: (RemoteKey) -> Unit,
    onKeyboard: () -> Unit,
    onMore: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Fit three keys plus both rockers on narrow phones without them touching.
        val rocker = 70.dp
        val gap = 10.dp
        val key = minOf(64.dp, (maxWidth - rocker * 2 - gap * 4) / 3)
        Row(Modifier.fillMaxWidth().height(key * 2 + 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            VolumeRocker(volume, muted, onUp = { onKey(RemoteKey.VOLUME_UP) }, onDown = { onKey(RemoteKey.VOLUME_DOWN) }, modifier = Modifier.width(rocker).fillMaxHeight())
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    RoundKey(Icons.Rounded.Keyboard, "Keyboard", size = key, onPress = onKeyboard)
                    RoundKey(Icons.Rounded.Home, "Home", size = key) { onKey(RemoteKey.HOME) }
                    RoundKey(Icons.Rounded.Dialpad, "Number pad and more keys", size = key, onPress = onMore)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    RoundKey(Icons.Rounded.VolumeOff, "Mute", size = key, tint = if (muted) Palette.red else Palette.onSurface) { onKey(RemoteKey.MUTE) }
                    MicKey(listening, key, onVoiceStart, onVoiceStop)
                    RoundKey(Icons.AutoMirrored.Rounded.Undo, "Back", size = key) { onKey(RemoteKey.BACK) }
                }
            }
            ChannelRocker(onUp = { onKey(RemoteKey.CHANNEL_UP) }, onDown = { onKey(RemoteKey.CHANNEL_DOWN) }, modifier = Modifier.width(rocker).fillMaxHeight())
        }
    }
}

/** Hold to talk. Asks for the microphone the first time. */
@Composable
private fun MicKey(listening: Boolean, size: androidx.compose.ui.unit.Dp, onStart: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHaptic.current
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    listening -> Palette.violet
                    pressed -> Palette.pressed
                    else -> Palette.surface
                },
            )
            .remotePress(onPressedChange = { down ->
                pressed = down
                if (down) {
                    haptic()
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        start()
                    } else {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                } else {
                    stop()
                }
            }) {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Mic, "Hold to talk", tint = Palette.onSurface, modifier = Modifier.size(size * 0.42f))
    }
}
