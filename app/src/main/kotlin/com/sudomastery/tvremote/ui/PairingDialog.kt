package com.sudomastery.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.sudomastery.tvremote.PairingState

@Composable
fun PairingDialog(state: PairingState, onSubmit: (String) -> Unit, onRetry: () -> Unit, onCancel: () -> Unit) {
    if (state == PairingState.Idle) return
    Dialog(onDismissRequest = onCancel) {
        CompositionLocalProvider(LocalContentColor provides Palette.onSurface) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Palette.surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                is PairingState.Connecting -> {
                    Title("Pairing with ${state.tv.name}")
                    Spacer(Modifier.height(20.dp))
                    CircularProgressIndicator(color = Palette.violet)
                    Spacer(Modifier.height(16.dp))
                    Body("Asking the TV for a code…")
                    Actions { TextButton(onClick = onCancel) { Text("Cancel", color = Palette.muted) } }
                }

                is PairingState.EnterCode -> CodeEntry(state, onSubmit, onCancel)

                is PairingState.Failed -> {
                    Title("Couldn't pair")
                    Spacer(Modifier.height(12.dp))
                    Body(state.message)
                    Actions {
                        TextButton(onClick = onCancel) { Text("Close", color = Palette.muted) }
                        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Palette.violet)) { Text("Try again") }
                    }
                }

                PairingState.Idle -> Unit
            }
        }
        }
    }
}

@Composable
private fun CodeEntry(state: PairingState.EnterCode, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(state.error) { if (state.error != null) code = "" }

    Title("Pairing with ${state.tv.name}")
    Spacer(Modifier.height(8.dp))
    Body("Enter the 6-character code shown on your TV.")
    Spacer(Modifier.height(20.dp))
    BasicTextField(
        value = code,
        onValueChange = { input ->
            val clean = input.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
            code = clean
            if (clean.length == 6 && !state.verifying) onSubmit(clean)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        modifier = Modifier.focusRequester(focus),
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(6) { i ->
                    val char = code.getOrNull(i)?.toString() ?: ""
                    val active = i == code.length
                    Box(
                        Modifier
                            .size(width = 40.dp, height = 52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Palette.surfaceHigh)
                            .border(1.5.dp, if (active) Palette.violet else Palette.pressed, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(char, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
    )
    if (state.error != null) {
        Spacer(Modifier.height(12.dp))
        Text(state.error, color = Palette.red, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
    Actions {
        TextButton(onClick = onCancel) { Text("Cancel", color = Palette.muted) }
        Button(
            onClick = { onSubmit(code) },
            enabled = code.length == 6 && !state.verifying,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.violet),
        ) {
            if (state.verifying) CircularProgressIndicator(Modifier.size(18.dp), color = Palette.onSurface, strokeWidth = 2.dp) else Text("Pair")
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)

@Composable
private fun Body(text: String) = Text(text, color = Palette.muted, fontSize = 15.sp, textAlign = TextAlign.Center)

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}
