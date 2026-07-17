package com.reedsutliff.talaria.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * An [AlertDialog] that prompts the user for a thread name and
 * calls [onCreate] with the entered text when "Create" is tapped.
 *
 * @param onDismiss Called when the dialog is dismissed (cancel, tap outside, etc.).
 * @param onCreate Called with the entered thread name string.
 */
@Composable
fun NewThreadDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var threadName by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "New Thread",
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            OutlinedTextField(
                value = threadName,
                onValueChange = { threadName = it },
                label = { Text("Thread name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(threadName.trim())
                },
                enabled = threadName.isNotBlank(),
            ) {
                Text(
                    text = "Create",
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
