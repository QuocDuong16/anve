package com.anve.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.ui.circle.CircleScreen
import com.anve.app.R
import com.anve.app.data.api.DeviceDto
import com.anve.app.data.api.DeviceSessionDto
import com.anve.app.data.api.UserDto
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.tracking.TrackingScreen

/**
 * M1 home: the account, the privacy consent switch and the device list.
 * Later milestones grow the dashboard around this (Live Map first), so it
 * deliberately stays a working screen rather than a menu of buttons.
 */
private enum class HomeTab(val labelRes: Int) {
    CIRCLE(R.string.tab_circle),
    LOCATION(R.string.tab_location),
    ACCOUNT(R.string.tab_account),
}

/**
 * The signed-in surface: a bottom bar with the circle tab (the family
 * object) and the account tab. Later milestones add the map tab here.
 */
@Composable
fun HomeScreen(container: AppContainer) {
    var tab by remember { mutableStateOf(HomeTab.CIRCLE) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {
                            Icon(
                                imageVector = when (entry) {
                                    HomeTab.CIRCLE -> Icons.Default.Groups
                                    HomeTab.LOCATION -> Icons.Default.LocationOn
                                    HomeTab.ACCOUNT -> Icons.Default.Person
                                },
                                contentDescription = stringResource(entry.labelRes),
                            )
                        },
                        label = { Text(stringResource(entry.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                HomeTab.CIRCLE -> CircleScreen(container = container)
                HomeTab.LOCATION -> TrackingScreen(container = container)
                HomeTab.ACCOUNT -> AccountContent(container = container)
            }
        }
    }
}

@Composable
private fun AccountContent(container: AppContainer) {
    val viewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(container.authRepository, container.trackingManager),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }

    HomeContent(
        state = state,
        onToggleSharing = viewModel::setLocationSharing,
        onRename = { showRenameDialog = true },
        onRevokeDevice = viewModel::revokeDevice,
        onSignOut = { showSignOutDialog = true },
        onDeleteAccount = { showDeleteDialog = true },
        onRetry = viewModel::refresh,
    )

    if (showRenameDialog) {
        RenameDialog(
            initial = state.user?.displayName.orEmpty(),
            onConfirm = { name ->
                viewModel.rename(name)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }
    if (showSignOutDialog) {
        SignOutDialog(
            onConfirm = {
                viewModel.signOut()
                showSignOutDialog = false
            },
            onDismiss = { showSignOutDialog = false },
        )
    }
    if (showDeleteDialog) {
        DeleteAccountDialog(
            onConfirm = {
                viewModel.deleteAccount()
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
fun HomeContent(
    state: HomeUiState,
    onToggleSharing: (Boolean) -> Unit,
    onRename: () -> Unit,
    onRevokeDevice: (String) -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onRetry: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Surface
        }

        val user = state.user
        if (user == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.home_load_failed),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
            return@Surface
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.error != null || state.message != null) {
                item {
                    Text(
                        text = state.error ?: state.message ?: "",
                        color = if (state.error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item { ProfileCard(user = user, onRename = onRename) }
            item { ConsentCard(user = user, onToggle = onToggleSharing) }
            item {
                Text(
                    text = stringResource(R.string.home_devices_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(state.devices, key = { it.id }) { device ->
                DeviceRow(
                    device = device,
                    onRevoke = { onRevokeDevice(device.id) },
                )
            }
            item {
                HorizontalDivider()
                OutlinedButton(
                    onClick = onSignOut,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.home_sign_out))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDeleteAccount,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.home_delete_account),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(user: UserDto, onRename: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = user.displayName.take(1).uppercase().ifBlank { "?" },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                Text(user.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    user.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRename) {
                Text(stringResource(R.string.action_rename))
            }
        }
    }
}

@Composable
private fun ConsentCard(user: UserDto, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.home_sharing_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.home_sharing_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = user.locationSharingEnabled,
                    onCheckedChange = onToggle,
                )
            }
            if (user.locationSharingEnabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.home_sharing_on_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceDto, onRevoke: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name.ifBlank { "Thiết bị" }, style = MaterialTheme.typography.titleSmall)
                    if (device.session?.current == true) {
                        Spacer(Modifier.size(8.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.home_device_this)) },
                        )
                    }
                }
                Text(
                    text = listOfNotNull(
                        device.platform,
                        device.appVersion?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRevoke) {
                Text(
                    stringResource(R.string.home_device_revoke),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.home_rename_label)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SignOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_sign_out_title)) },
        text = { Text(stringResource(R.string.home_sign_out_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.home_sign_out)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun DeleteAccountDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_delete_title)) },
        text = { Text(stringResource(R.string.home_delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.home_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun HomeContentPreview() {
    val user = UserDto(
        id = "u1",
        email = "me@example.com",
        displayName = "Mai",
        avatarUrl = null,
        locationSharingEnabled = false,
        createdAt = "2026-10-25T10:00:00Z",
    )
    val device = DeviceDto(
        id = "d1",
        name = "Pixel 8",
        platform = "android",
        appVersion = "0.2.0-debug",
        createdAt = "2026-10-25T10:00:00Z",
        lastSeenAt = "2026-10-25T12:00:00Z",
        session = DeviceSessionDto(
            id = "s1",
            createdAt = "2026-10-25T10:00:00Z",
            lastUsedAt = "2026-10-25T12:00:00Z",
            expiresAt = "2026-11-24T10:00:00Z",
            current = true,
        ),
    )
    AnVeTheme {
        HomeContent(
            state = HomeUiState(loading = false, user = user, devices = listOf(device)),
            onToggleSharing = {},
            onRename = {},
            onRevokeDevice = {},
            onSignOut = {},
            onDeleteAccount = {},
            onRetry = {},
        )
    }
}
