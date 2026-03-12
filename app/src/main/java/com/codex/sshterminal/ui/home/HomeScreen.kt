package com.codex.sshterminal.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codex.sshterminal.R
import com.codex.sshterminal.data.local.SavedConnectionEntity
import kotlinx.coroutines.delay

/** Collects the HomeViewModel state and forwards user actions into the home screen UI. */
@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearUserMessage()
        }
    }

    HomeScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onNameChanged = viewModel::updateName,
        onHostChanged = viewModel::updateHost,
        onPortChanged = viewModel::updatePort,
        onUsernameChanged = viewModel::updateUsername,
        onPasswordChanged = viewModel::updatePassword,
        onPrivateKeyChanged = viewModel::updatePrivateKey,
        onUsePrivateKeyChanged = viewModel::updateUsePrivateKey,
        onSaveSecretChanged = viewModel::updateSaveSecret,
        onSaveClicked = viewModel::saveCurrentConnection,
        onClearFieldsClicked = viewModel::clearFields,
        onConnectClicked = viewModel::onConnectRequested,
        onSelectConnection = viewModel::selectSavedConnection,
        onDeleteConnection = viewModel::deleteSavedConnection,
        onKeyPassphraseChanged = viewModel::updateKeyPassphrase,
        onSubmitKeyPassphrase = viewModel::submitKeyPassphrase,
        onDismissKeyPassphrase = viewModel::dismissKeyPassphrasePrompt,
    )
}

/** Renders the start screen, including the connection form and the saved-connections list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    uiState: HomeUiState,
    snackbarHostState: SnackbarHostState,
    onNameChanged: (String) -> Unit,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onPrivateKeyChanged: (String) -> Unit,
    onUsePrivateKeyChanged: (Boolean) -> Unit,
    onSaveSecretChanged: (Boolean) -> Unit,
    onSaveClicked: () -> Unit,
    onClearFieldsClicked: () -> Unit,
    onConnectClicked: () -> Unit,
    onSelectConnection: (SavedConnectionEntity) -> Unit,
    onDeleteConnection: (SavedConnectionEntity) -> Unit,
    onKeyPassphraseChanged: (String) -> Unit,
    onSubmitKeyPassphrase: () -> Unit,
    onDismissKeyPassphrase: () -> Unit,
) {
    var pendingDeleteConnection by remember { mutableStateOf<SavedConnectionEntity?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var suppressFieldFocus by remember { mutableStateOf(false) }

    // Compose can restore the previously focused field during navigation, so we suppress field focus briefly on return.
    LaunchedEffect(uiState.showConnectionScreen) {
        if (uiState.showConnectionScreen) {
            suppressFieldFocus = true
            repeat(12) {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                delay(100)
            }
            suppressFieldFocus = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            item {
                ConnectionFormCard(
                    uiState = uiState,
                    onNameChanged = onNameChanged,
                    onHostChanged = onHostChanged,
                    onPortChanged = onPortChanged,
                    onUsernameChanged = onUsernameChanged,
                    onPasswordChanged = onPasswordChanged,
                    onPrivateKeyChanged = onPrivateKeyChanged,
                    onUsePrivateKeyChanged = onUsePrivateKeyChanged,
                    onSaveSecretChanged = onSaveSecretChanged,
                    onSaveClicked = onSaveClicked,
                    onClearFieldsClicked = onClearFieldsClicked,
                    onConnectClicked = onConnectClicked,
                    fieldsCanFocus = !suppressFieldFocus,
                )
            }

            if (uiState.savedConnections.isNotEmpty()) {
                item {
                    Text(
                        text = "Saved Connections",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                items(uiState.savedConnections, key = { connection -> connection.id }) { connection ->
                    SavedConnectionRow(
                        connection = connection,
                        isSelected = uiState.selectedConnectionId == connection.id,
                        onClick = { onSelectConnection(connection) },
                        onLongClick = { pendingDeleteConnection = connection },
                    )
                }
            }
        }
    }

    pendingDeleteConnection?.let { connection ->
        AlertDialog(
            onDismissRequest = { pendingDeleteConnection = null },
            title = { Text("Delete saved connection") },
            text = { Text("Delete '${connection.name}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteConnection = null
                        onDeleteConnection(connection)
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteConnection = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    uiState.keyPassphrasePrompt?.let { prompt ->
        KeyPassphraseDialog(
            prompt = prompt,
            onValueChanged = onKeyPassphraseChanged,
            onConfirm = onSubmitKeyPassphrase,
            onDismiss = onDismissKeyPassphrase,
        )
    }
}

/** Hosts the editable connection fields plus the save, clear, and connect actions. */
@Composable
private fun ConnectionFormCard(
    uiState: HomeUiState,
    onNameChanged: (String) -> Unit,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onPrivateKeyChanged: (String) -> Unit,
    onUsePrivateKeyChanged: (Boolean) -> Unit,
    onSaveSecretChanged: (Boolean) -> Unit,
    onSaveClicked: () -> Unit,
    onClearFieldsClicked: () -> Unit,
    onConnectClicked: () -> Unit,
    fieldsCanFocus: Boolean,
) {
    // Reset the reveal toggles when the selected row changes so newly loaded secrets stay hidden by default.
    var isPasswordVisible by rememberSaveable(uiState.selectedConnectionId, uiState.form.password) { mutableStateOf(false) }
    var isPrivateKeyVisible by rememberSaveable(uiState.selectedConnectionId, uiState.form.privateKey) { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = uiState.form.name,
                onValueChange = onNameChanged,
                label = { Text("Name") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties { canFocus = fieldsCanFocus },
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = uiState.form.host,
                    onValueChange = onHostChanged,
                    label = { Text("Host or IP") },
                    modifier = Modifier
                        .weight(0.72f)
                        .focusProperties { canFocus = fieldsCanFocus },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = uiState.form.port,
                    onValueChange = onPortChanged,
                    label = { Text("Port") },
                    modifier = Modifier
                        .weight(0.28f)
                        .focusProperties { canFocus = fieldsCanFocus },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }

            if (uiState.form.usePrivateKey && isPrivateKeyVisible) {
                OutlinedTextField(
                    value = uiState.form.username,
                    onValueChange = onUsernameChanged,
                    label = { Text("Username") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusProperties { canFocus = fieldsCanFocus },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = uiState.form.privateKey,
                    onValueChange = onPrivateKeyChanged,
                    label = { Text("Private Key") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusProperties { canFocus = fieldsCanFocus },
                    singleLine = false,
                    minLines = 6,
                    maxLines = 12,
                    visualTransformation = VisualTransformation.None,
                    trailingIcon = {
                        TextButton(onClick = { isPrivateKeyVisible = false }) {
                            Text("Hide")
                        }
                    },
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = uiState.form.username,
                        onValueChange = onUsernameChanged,
                        label = { Text("Username") },
                        modifier = Modifier
                            .weight(0.45f)
                            .focusProperties { canFocus = fieldsCanFocus },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = if (uiState.form.usePrivateKey) uiState.form.privateKey else uiState.form.password,
                        onValueChange = if (uiState.form.usePrivateKey) onPrivateKeyChanged else onPasswordChanged,
                        label = { Text(if (uiState.form.usePrivateKey) "Private Key" else "Password") },
                        modifier = Modifier
                            .weight(0.55f)
                            .focusProperties { canFocus = fieldsCanFocus },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (uiState.form.usePrivateKey) KeyboardType.Text else KeyboardType.Password,
                        ),
                        singleLine = true,
                        visualTransformation = if (uiState.form.usePrivateKey) {
                            PasswordVisualTransformation()
                        } else if (isPasswordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    if (uiState.form.usePrivateKey) {
                                        isPrivateKeyVisible = true
                                    } else {
                                        isPasswordVisible = !isPasswordVisible
                                    }
                                },
                            ) {
                                Text(
                                    if (uiState.form.usePrivateKey) {
                                        "Show"
                                    } else if (isPasswordVisible) {
                                        "Hide"
                                    } else {
                                        "Show"
                                    },
                                )
                            }
                        },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = uiState.form.usePrivateKey,
                        onCheckedChange = onUsePrivateKeyChanged,
                    )
                    Text("Use Private Key")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = uiState.form.saveSecret,
                        onCheckedChange = onSaveSecretChanged,
                    )
                    Text(if (uiState.form.usePrivateKey) "Save Key" else "Save Password")
                }
                TextButton(onClick = onClearFieldsClicked) {
                    Text("Clear fields")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onSaveClicked,
                    enabled = uiState.form.isSaveEnabled && !uiState.isSaving,
                    modifier = Modifier.weight(1f),
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (uiState.isEditingSavedConnection) "Update" else "Save")
                    }
                }
                Button(
                    onClick = onConnectClicked,
                    enabled = uiState.form.isConnectEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Connect")
                }
            }
        }
    }
}

/** Prompts for the private-key passphrase only when the pasted key actually needs one. */
@Composable
private fun KeyPassphraseDialog(
    prompt: KeyPassphrasePromptState,
    onValueChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var isVisible by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Private Key Passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(prompt.message)
                OutlinedTextField(
                    value = prompt.passphrase,
                    onValueChange = onValueChanged,
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { isVisible = !isVisible }) {
                            Text(if (isVisible) "Hide" else "Show")
                        }
                    },
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/** Represents one saved connection row; tap loads it and long-press requests deletion. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedConnectionRow(
    connection: SavedConnectionEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = connection.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${connection.username}@${connection.host}:${connection.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}






