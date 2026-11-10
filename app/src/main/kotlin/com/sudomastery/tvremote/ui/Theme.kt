package com.sudomastery.tvremote.ui

import android.view.HapticFeedbackConstants
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView

object Palette {
    val background = Color(0xFF141416)
    val surface = Color(0xFF232327)
    val surfaceHigh = Color(0xFF2D2D33)
    val pressed = Color(0xFF3A3A42)
    val onSurface = Color(0xFFECECF1)
    val muted = Color(0xFF9A9AA6)
    val violet = Color(0xFF8B5CF6)
    val blue = Color(0xFF3B82F6)
    val green = Color(0xFF34C759)
    val amber = Color(0xFFFFB020)
    val red = Color(0xFFFF5A5F)
    val accent = Brush.linearGradient(listOf(violet, blue))
    val accentSweep = Brush.sweepGradient(listOf(violet, blue, violet))
}

/** Fires a light tick on key presses when haptics are on. */
val LocalHaptic = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun RemoteTheme(haptics: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    val tick: () -> Unit = if (haptics) {
        { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    } else {
        {}
    }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.violet,
            onPrimary = Color.White,
            secondary = Palette.blue,
            background = Palette.background,
            onBackground = Palette.onSurface,
            surface = Palette.surface,
            onSurface = Palette.onSurface,
            surfaceContainer = Palette.surface,
            surfaceContainerHigh = Palette.surfaceHigh,
            surfaceContainerLow = Palette.surface,
            onSurfaceVariant = Palette.muted,
            outline = Palette.pressed,
            error = Palette.red,
        ),
    ) {
        CompositionLocalProvider(LocalHaptic provides tick, content = content)
    }
}
