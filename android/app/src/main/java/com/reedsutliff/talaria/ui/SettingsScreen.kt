package com.reedsutliff.talaria.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.reedsutliff.talaria.model.AppSettings

/**
 * Full-screen settings page for Talaria.
 *
 * Allows the user to edit server connection parameters and toggle
 * speech/text features.  Changes are written back via [onUpdateSettings]
 * only when the user explicitly taps "Save".
 *
 * @param settings Current [AppSettings] from the ViewModel.
 * @param onUpdateSettings Callback invoked with the modified settings on save.
 * @param onBack Callback to navigate back (e.g. close the settings screen).
 * @param onCheckForUpdate Callback to trigger a manual update check.
 * @param hermesProfiles Available Hermes profile names from the server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdateSettings: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onCheckForUpdate: () -> Unit = {},
    hermesProfiles: List<String> = emptyList(),
) {
    var serverUrl by rememberSaveable { mutableStateOf(settings.serverUrl) }
    var cfClientId by rememberSaveable { mutableStateOf(settings.cfClientId) }
    var cfClientSecret by rememberSaveable { mutableStateOf(settings.cfClientSecret) }
    var sttEnabled by rememberSaveable { mutableStateOf(settings.sttEnabled) }
    var ttsEnabled by rememberSaveable { mutableStateOf(settings.ttsEnabled) }
    var selectedProfile by rememberSaveable { mutableStateOf(settings.selectedProfile) }
    var profileExpanded by rememberSaveable { mutableStateOf(false) }

    var showClientId by rememberSaveable { mutableStateOf(false) }
    var showClientSecret by rememberSaveable { mutableStateOf(false) }

    val hasChanges = serverUrl != settings.serverUrl ||
            cfClientId != settings.cfClientId ||
            cfClientSecret != settings.cfClientSecret ||
            sttEnabled != settings.sttEnabled ||
            ttsEnabled != settings.ttsEnabled ||
            selectedProfile != settings.selectedProfile

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // ── Server ──────────────────────────────────────
            SectionHeader("Server")

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("WebSocket URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = cfClientId,
                onValueChange = { cfClientId = it },
                label = { Text("CF-Access Client ID") },
                singleLine = true,
                visualTransformation = if (showClientId) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showClientId = !showClientId }) {
                        Icon(
                            imageVector = if (showClientId) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showClientId) "Hide Client ID" else "Show Client ID",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = cfClientSecret,
                onValueChange = { cfClientSecret = it },
                label = { Text("CF-Access Client Secret") },
                singleLine = true,
                visualTransformation = if (showClientSecret) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showClientSecret = !showClientSecret }) {
                        Icon(
                            imageVector = if (showClientSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showClientSecret) "Hide Client Secret" else "Show Client Secret",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(24.dp))

            // ── Hermes Profile ─────────────────────────────
            SectionHeader("Hermes Profile")

            ExposedDropdownMenuBox(
                expanded = profileExpanded,
                onExpandedChange = { profileExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedProfile.ifEmpty { "Default" },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Active Profile") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    shape = RoundedCornerShape(12.dp),
                )
                ExposedDropdownMenu(
                    expanded = profileExpanded,
                    onDismissRequest = { profileExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Default") },
                        onClick = {
                            selectedProfile = ""
                            profileExpanded = false
                        },
                    )
                    hermesProfiles.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile) },
                            onClick = {
                                selectedProfile = profile
                                profileExpanded = false
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Audio ───────────────────────────────────────
            SectionHeader("Audio")

            SettingsToggle(
                label = "Speech-to-Text (STT)",
                checked = sttEnabled,
                onCheckedChange = { sttEnabled = it },
            )

            Spacer(Modifier.height(8.dp))

            SettingsToggle(
                label = "Text-to-Speech (TTS)",
                checked = ttsEnabled,
                onCheckedChange = { ttsEnabled = it },
            )

            Spacer(Modifier.height(32.dp))

            // ── Action buttons ──────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Back")
                }

                Spacer(Modifier.width(12.dp))

                Button(
                    onClick = {
                        onUpdateSettings(
                            settings.copy(
                                serverUrl = serverUrl.trim(),
                                cfClientId = cfClientId.trim(),
                                cfClientSecret = cfClientSecret.trim(),
                                sttEnabled = sttEnabled,
                                ttsEnabled = ttsEnabled,
                                selectedProfile = selectedProfile,
                            )
                        )
                    },
                    enabled = hasChanges,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Save")
                }
            }

            Spacer(Modifier.height(32.dp))

            // ── Version info ────────────────────────────────
            Text(
                text = "Talaria v${com.reedsutliff.talaria.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onCheckForUpdate,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Check for Updates")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Reusable helpers ───────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SettingsToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
