package com.sudomastery.tvremote.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 110L
private const val LONG_PRESS_MS = 550L
const val DISABLED_ALPHA = 0.3f

/**
 * Press handling tuned for a remote: fires on touch-down (no waiting for release), auto-repeats
 * while held when [repeat] is set, and supports a separate long-press for keys like OK.
 */
fun Modifier.remotePress(
    onPressedChange: (Boolean) -> Unit = {},
    repeat: Boolean = false,
    onLongStart: (() -> Unit)? = null,
    onLongEnd: (() -> Unit)? = null,
    onPress: () -> Unit,
): Modifier = this.pointerInput(repeat, onLongStart != null) {
    coroutineScope {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false).consume()
            onPressedChange(true)
            if (onLongStart != null) {
                val released = withTimeoutOrNull(LONG_PRESS_MS) { waitForUpOrCancellation() }
                if (released != null) {
                    onPress()
                } else {
                    onLongStart()
                    waitForUpOrCancellation()
                    onLongEnd?.invoke()
                }
            } else {
                onPress()
                val repeater = if (repeat) launch {
                    delay(REPEAT_DELAY_MS)
                    while (true) {
                        onPress()
                        delay(REPEAT_INTERVAL_MS)
                    }
                } else null
                waitForUpOrCancellation()
                repeater?.cancel()
            }
            onPressedChange(false)
        }
    }
}

@Composable
fun RoundKey(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    tint: Color = Palette.onSurface,
    background: Color = Palette.surface,
    repeat: Boolean = false,
    enabled: Boolean = true,
    onPress: () -> Unit,
) {
    val haptic = LocalHaptic.current
    val action by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(90), label = "press")
    Box(
        modifier
            .size(size)
            .scale(scale)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .background(if (pressed) Palette.pressed else background)
            .then(if (enabled) Modifier.remotePress(onPressedChange = { pressed = it }, repeat = repeat) { haptic(); action() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.42f))
    }
}

/** Tall rocker for volume and channel, like the physical remote. */
@Composable
fun Rocker(
    label: String,
    value: String?,
    upIcon: ImageVector,
    downIcon: ImageVector,
    onUp: () -> Unit,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(40.dp))
            .background(Palette.surface)
            .then(if (highlight) Modifier.border(1.5.dp, Palette.accent, RoundedCornerShape(40.dp)) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        RockerHalf(upIcon, "$label up", onUp, Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Palette.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            if (value != null) Text(value, color = Palette.muted, fontSize = 13.sp)
        }
        RockerHalf(downIcon, "$label down", onDown, Modifier.weight(1f))
    }
}

@Composable
private fun RockerHalf(icon: ImageVector, description: String, onPress: () -> Unit, modifier: Modifier) {
    val haptic = LocalHaptic.current
    val action by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .background(if (pressed) Palette.pressed else Color.Transparent)
            .remotePress(onPressedChange = { pressed = it }, repeat = true) { haptic(); action() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Palette.onSurface, modifier = Modifier.size(30.dp))
    }
}

@Composable
fun VolumeRocker(level: Int?, muted: Boolean, onUp: () -> Unit, onDown: () -> Unit, modifier: Modifier = Modifier) =
    Rocker(
        label = "VOL",
        value = when {
            muted -> "muted"
            level != null -> level.toString()
            else -> null
        },
        upIcon = Icons.Rounded.Add,
        downIcon = Icons.Rounded.Remove,
        onUp = onUp,
        onDown = onDown,
        modifier = modifier,
    )

@Composable
fun ChannelRocker(onUp: () -> Unit, onDown: () -> Unit, modifier: Modifier = Modifier) =
    Rocker("CH", null, Icons.Rounded.KeyboardArrowUp, Icons.Rounded.KeyboardArrowDown, onUp, onDown, modifier)

enum class PadKey { UP, DOWN, LEFT, RIGHT, OK }

/**
 * Circular D-pad. Directions fire on touch-down and repeat while held; OK supports long-press
 * (sent to the TV as a real key hold, which opens context menus).
 */
@Composable
fun DPad(
    onKey: (PadKey) -> Unit,
    onOkLongStart: () -> Unit,
    onOkLongEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHaptic.current
    val keyAction by rememberUpdatedState(onKey)
    val longStart by rememberUpdatedState(onOkLongStart)
    val longEnd by rememberUpdatedState(onOkLongEnd)
    var pressed by remember { mutableStateOf<PadKey?>(null) }

    Box(
        modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val key = hitTest(down.position, Size(size.width.toFloat(), size.height.toFloat())) ?: return@awaitEachGesture
                        down.consume()
                        pressed = key
                        haptic()
                        if (key == PadKey.OK) {
                            val released = withTimeoutOrNull(LONG_PRESS_MS) { waitForUpConsuming() }
                            if (released != null) {
                                keyAction(PadKey.OK)
                            } else {
                                haptic()
                                longStart()
                                waitForUpConsuming()
                                longEnd()
                            }
                        } else {
                            keyAction(key)
                            val repeater = launch {
                                delay(REPEAT_DELAY_MS)
                                while (true) {
                                    keyAction(key)
                                    delay(REPEAT_INTERVAL_MS)
                                }
                            }
                            waitForUpConsuming()
                            repeater.cancel()
                        }
                        pressed = null
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            val r = size.minDimension / 2 - stroke
            drawCircle(Palette.surface, r)
            pressed?.takeIf { it != PadKey.OK }?.let { key ->
                val start = when (key) {
                    PadKey.RIGHT -> -45f
                    PadKey.DOWN -> 45f
                    PadKey.LEFT -> 135f
                    else -> 225f
                }
                drawArc(
                    Palette.pressed, start, 90f, useCenter = true,
                    topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2),
                )
            }
            drawCircle(Palette.accentSweep, r, style = Stroke(stroke))
            val inner = r * INNER_RATIO
            drawCircle(if (pressed == PadKey.OK) Palette.pressed else Palette.surfaceHigh, inner)
            drawCircle(Palette.accent, inner, style = Stroke(stroke * 1.5f))
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val inset = maxWidth * 0.09f
            Icon(Icons.Rounded.KeyboardArrowUp, "Up", tint = Palette.onSurface, modifier = Modifier.align(Alignment.TopCenter).padding(top = inset).size(40.dp))
            Icon(Icons.Rounded.KeyboardArrowDown, "Down", tint = Palette.onSurface, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = inset).size(40.dp))
            Icon(Icons.Rounded.KeyboardArrowLeft, "Left", tint = Palette.onSurface, modifier = Modifier.align(Alignment.CenterStart).padding(start = inset).size(40.dp))
            Icon(Icons.Rounded.KeyboardArrowRight, "Right", tint = Palette.onSurface, modifier = Modifier.align(Alignment.CenterEnd).padding(end = inset).size(40.dp))
        }
        Text("OK", color = Palette.muted, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

private const val INNER_RATIO = 0.3f

private fun hitTest(p: Offset, size: Size): PadKey? {
    val cx = size.width / 2
    val cy = size.height / 2
    val r = minOf(cx, cy)
    val dx = p.x - cx
    val dy = p.y - cy
    val dist = hypot(dx, dy)
    if (dist > r) return null
    if (dist < r * INNER_RATIO) return PadKey.OK
    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) // 0 = right, 90 = down
    return when {
        angle >= -45 && angle < 45 -> PadKey.RIGHT
        angle >= 45 && angle < 135 -> PadKey.DOWN
        angle >= -135 && angle < -45 -> PadKey.UP
        else -> PadKey.LEFT
    }
}

/**
 * Swipe surface: every ~[stepDp] of travel moves focus one step in that direction, a tap is OK.
 */
@Composable
fun Touchpad(onKey: (PadKey) -> Unit, modifier: Modifier = Modifier, stepDp: Dp = 44.dp) {
    val haptic = LocalHaptic.current
    val keyAction by rememberUpdatedState(onKey)
    var active by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(36.dp))
            .background(if (active) Palette.surfaceHigh else Palette.surface)
            .border(1.5.dp, Palette.accent, RoundedCornerShape(36.dp))
            .pointerInput(Unit) {
                val step = stepDp.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    active = true
                    var accX = 0f
                    var accY = 0f
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        change.consume()
                        accX += delta.x
                        accY += delta.y
                        if (!moved && hypot(accX, accY) > slop) moved = true
                        // Emit along the dominant axis only, so diagonal swipes don't zig-zag.
                        while (abs(accX) >= step && abs(accX) >= abs(accY)) {
                            keyAction(if (accX > 0) PadKey.RIGHT else PadKey.LEFT)
                            haptic()
                            accX -= step * if (accX > 0) 1 else -1
                        }
                        while (abs(accY) >= step && abs(accY) > abs(accX)) {
                            keyAction(if (accY > 0) PadKey.DOWN else PadKey.UP)
                            haptic()
                            accY -= step * if (accY > 0) 1 else -1
                        }
                    }
                    if (!moved) {
                        haptic()
                        keyAction(PadKey.OK)
                    }
                    active = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("Swipe to move · tap to select", color = Palette.muted, fontSize = 14.sp)
    }
}

/** waitForUpOrCancellation that also consumes moves, so a parent never steals the gesture. */
private suspend fun AwaitPointerEventScope.waitForUpConsuming(): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        val change = event.changes.firstOrNull() ?: return null
        if (!change.pressed) return change
    }
}
