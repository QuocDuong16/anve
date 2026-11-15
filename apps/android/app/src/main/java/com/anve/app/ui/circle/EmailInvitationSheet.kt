package com.anve.app.ui.circle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anve.app.R
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.theme.isDarkSurface
import com.anve.app.ui.theme.rememberInkOnSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailInvitationSheet(state: PrivateInvitationsState, online: Boolean, onDismiss: () -> Unit, onMode: (String) -> Unit,
    onEmail: (String) -> Unit, onHours: (Int) -> Unit, onCreate: () -> Unit, onCheck: () -> Unit) {
    var showExpiry by remember { mutableStateOf(false) }
    val editable = !state.busy && !state.unknown
    val colors = MaterialTheme.colorScheme
    val dark = isDarkSurface()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.86f)
            .padding(horizontal = 16.dp).testTag("email-invite-sheet")) {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { Text(stringResource(R.string.invite_sheet_title), style = MaterialTheme.typography.headlineSmall) }
                item { FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("EMAIL" to stringResource(R.string.invite_mode_email), "LINK" to stringResource(R.string.invite_mode_link)).forEach { (mode, label) ->
                        Surface(Modifier.weight(1f).heightIn(min = 48.dp).selectable(state.mode == mode, enabled = editable,
                            role = Role.RadioButton, onClick = { onMode(mode) }), shape = RoundedCornerShape(12.dp),
                            border = if (state.mode == mode) null else BorderStroke(1.dp, colors.outline),
                            color = if (state.mode == mode) colors.primaryContainer else colors.surface,
                            contentColor = if (state.mode == mode) colors.onPrimaryContainer else colors.onSurface) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = state.mode == mode, onClick = null, enabled = editable,
                                    colors = RadioButtonDefaults.colors(selectedColor = colors.onPrimaryContainer))
                                Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                } }
                if (state.mode == "EMAIL") item {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, colors.outlineVariant)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.invite_email_section), style = MaterialTheme.typography.titleSmall,
                                color = rememberInkOnSurface())
                            // Inline validation so a wrong email surfaces while
                            // typing, not only when the create call rejects it;
                            // the controller keeps the same rule as backstop.
                            val emailInvalid = state.mode == "EMAIL" && state.email.isNotBlank() && !inviteEmailValid(state.email.trim().lowercase())
                            OutlinedTextField(state.email, onEmail, placeholder = { Text(stringResource(R.string.invite_email_placeholder)) },
                                label = { Text(stringResource(R.string.invite_email_label)) }, singleLine = true, enabled = editable,
                                isError = emailInvalid,
                                supportingText = if (emailInvalid) {
                                    { Text(stringResource(R.string.invite_email_invalid)) }
                                } else null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                                modifier = Modifier.fillMaxWidth().testTag("invite-email"))
                        }
                    }
                }
                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Info, null, tint = rememberInkOnSurface())
                    // The email note explains the same topic as the info line, so
                    // both read as one info block: a first-time sender otherwise
                    // parses three stacked explanation blocks before any error.
                    // The privacy warning below stays separate — its amber surface
                    // is a different visual signal than info, and merging it here
                    // would hide that distinction.
                    if (state.mode == "EMAIL") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.invite_email_info), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.invite_email_note), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Text(stringResource(R.string.invite_link_info), style = MaterialTheme.typography.bodyMedium)
                    }
                } }
                item { Surface(Modifier.fillMaxWidth(), color = if (dark) colors.surfaceVariant else colors.secondaryContainer,
                    contentColor = if (dark) colors.onSurfaceVariant else colors.onSecondaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.WarningAmber, null)
                        Text(stringResource(R.string.invite_privacy_warning), style = MaterialTheme.typography.bodyMedium)
                    }
                } }
                item { Box {
                    TextButton(onClick = { showExpiry = true }, enabled = editable,
                        colors = ButtonDefaults.textButtonColors(contentColor = rememberInkOnSurface()), modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Schedule, null, Modifier.size(20.dp))
                        Text(stringResource(
                            R.string.invite_uses_summary,
                            if (state.hours == 168) stringResource(R.string.invite_expiry_week) else stringResource(R.string.invite_expiry_hours, state.hours),
                        ), Modifier.padding(horizontal = 8.dp))
                        Icon(Icons.Outlined.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = showExpiry && editable, onDismissRequest = { showExpiry = false }) {
                        listOf(
                            24 to stringResource(R.string.invite_expiry_hours, 24),
                            48 to stringResource(R.string.invite_expiry_hours, 48),
                            168 to stringResource(R.string.invite_expiry_week),
                        ).forEach { (hours, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { onHours(hours); showExpiry = false })
                        }
                    }
                } }
                if (!online) item { Text(stringResource(R.string.invite_offline)) }
                state.errorRes?.let { res -> item { AnveErrorNotice(stringResource(res)) } }
                if (state.busy) item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp)); Text(stringResource(R.string.invite_busy))
                } }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = if (state.unknown) onCheck else onCreate,
                enabled = online && !state.busy && (state.unknown || state.mode == "LINK" || inviteEmailValid(state.email.trim().lowercase())),
                colors = ButtonDefaults.buttonColors(containerColor = if (dark) colors.primaryContainer else colors.primary,
                    contentColor = if (dark) colors.onPrimaryContainer else colors.onPrimary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("invite-submit")) {
                Text(if (state.unknown) stringResource(R.string.invite_check_result) else stringResource(R.string.invite_create))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Preview
@Composable
private fun EmailInvitationPreview() { AnVeTheme { EmailInvitationSheet(PrivateInvitationsState(open = true), true, {}, {}, {}, {}, {}, {}) } }
