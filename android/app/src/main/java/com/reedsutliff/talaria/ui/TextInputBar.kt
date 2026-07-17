package com.reedsutliff.talaria.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Text input bar for sending text messages.
 *
 * Contains an OutlinedTextField, a Send button, and a mode-toggle button
 * to switch between TEXT and VOICE input modes.
 *
 * @param onSend Called with the trimmed text when the user sends a message.
 * @param onToggleMode Called when the user taps the mode toggle button.
 * @param isTextMode Whether the current input mode is TEXT (true) or VOICE (false).
 */
@Composable
fun TextInputBar(
    onSend: (String) -> Unit,
    onToggleMode: () -> Unit,
    isTextMode: Boolean,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }

    fun send() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            onSend(trimmed)
            text = ""
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Type a message...") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )

        Spacer(Modifier.width(4.dp))

        // Send button — visible when there's text
        if (text.isNotBlank()) {
            IconButton(onClick = { send() }) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send message",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Mode toggle button — switches between TEXT and VOICE
        IconButton(onClick = onToggleMode) {
            Icon(
                imageVector = if (isTextMode) Icons.Default.Mic else Icons.Default.Keyboard,
                contentDescription = if (isTextMode) "Switch to voice input" else "Switch to text input",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
