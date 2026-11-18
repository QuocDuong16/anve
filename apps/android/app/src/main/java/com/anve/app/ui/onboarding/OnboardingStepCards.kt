package com.anve.app.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.anve.app.R
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.ui.brand.AnVeFamilySceneIllustration
import com.anve.app.ui.brand.AnVeHomeEmblem
import com.anve.app.ui.brand.AnVeIllustratedBanner
import com.anve.app.ui.common.AnVeSectionHeader
import com.anve.app.ui.common.AnveErrorNotice
import com.anve.app.ui.common.StitchBadgeKind
import com.anve.app.ui.common.StitchLoadingSkeletonCard
import com.anve.app.ui.common.StitchSemanticBadge
import com.anve.app.ui.places.PlaceSearchOutcome
import com.anve.app.ui.places.PlaceSuggestion
import com.anve.app.ui.theme.LocalStitchColors

@Composable
internal fun OnboardingTopProgress(
    stepNumber: Int,
    stepCount: Int,
    canGoBack: Boolean,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (canGoBack) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (index in 1..stepCount) {
                Surface(
                    shape = CircleShape,
                    color = if (index <= stepNumber) colors.primaryContainer else colors.surfaceContainerHighest,
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp),
                ) {}
            }
        }
        Surface(
            shape = CircleShape,
            color = colors.surfaceContainerLow,
            border = BorderStroke(1.dp, LocalStitchColors.current.hairline),
        ) {
            Text(
                text = stringResource(R.string.onboarding_step_badge, stepNumber, stepCount),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
internal fun WelcomeStepContent(
    onStartGuided: () -> Unit,
    onSkipToHome: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        AnVeIllustratedBanner {
            AnVeFamilySceneIllustration(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.onboarding_welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.onboarding_welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onStartGuided,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("onboarding-welcome-start"),
        ) {
            Text(stringResource(R.string.onboarding_welcome_start))
        }
        OutlinedButton(
            onClick = onSkipToHome,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("onboarding-welcome-skip"),
        ) {
            Text(stringResource(R.string.onboarding_welcome_skip))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FamilyStepContent(
    state: OnboardingUiState,
    onSelectMode: (OnboardingFamilyMode) -> Unit,
    onCircleNameChange: (String) -> Unit,
    onCreateCircle: () -> Unit,
    onCheckCircleAgain: () -> Unit,
    onCreateAndShareInvite: () -> Unit,
    onCheckInviteAgain: () -> Unit,
    onShareInviteAgain: () -> Unit,
    onCopyInviteCode: (String) -> Unit,
    onJoinCodeChange: (String) -> Unit,
    onReviewManualInvite: (String) -> Unit,
    onReviewReceivedInvite: (String) -> Unit,
    onRefreshFamily: () -> Unit,
    onContinue: () -> Unit,
    onSkipStep: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val suggestions = listOf(
        stringResource(R.string.onboarding_family_suggest_1),
        stringResource(R.string.onboarding_family_suggest_2),
        stringResource(R.string.onboarding_family_suggest_3),
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.onboarding_family_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )

        if (!state.online) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = colors.secondaryContainer,
                    contentColor = colors.onSecondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_offline_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        if (state.loadingFamily && state.circle == null) {
            StitchLoadingSkeletonCard(rows = 2)
        } else if (state.circle != null) {
            OutlinedCard(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.5.dp, colors.primaryContainer),
                colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AnVeSectionHeader(
                            title = state.circle.name,
                            subtitle = stringResource(
                                if (state.isOwner) R.string.circle_role_owner else R.string.circle_role_member,
                            ),
                        )
                    }
                    StitchSemanticBadge(
                        text = stringResource(R.string.onboarding_circle_created_badge, state.circle.name),
                        kind = StitchBadgeKind.SAFE_HOME,
                    )
                    if (state.isOwner) {
                        HorizontalDivider(color = stitch.hairline)
                        Text(
                            text = stringResource(R.string.onboarding_invite_section_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.onboarding_invite_section_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        state.inviteErrorRes?.let { err ->
                            AnveErrorNotice(
                                text = stringResource(err),
                                onRetry = if (state.inviteUnknown) onCheckInviteAgain else onCreateAndShareInvite,
                                embedded = true,
                            )
                        }
                        val createdInvite = state.createdInvite
                        if (createdInvite == null) {
                            FilledTonalButton(
                                onClick = if (state.inviteUnknown) onCheckInviteAgain else onCreateAndShareInvite,
                                enabled = !state.inviteBusy && state.online,
                                shape = CircleShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("onboarding-invite-create"),
                            ) {
                                if (state.inviteBusy) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                } else {
                                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    stringResource(
                                        if (state.inviteUnknown) R.string.onboarding_circle_check_again else R.string.onboarding_invite_create_share,
                                    ),
                                )
                            }
                        } else {
                            HorizontalDivider(color = stitch.hairline)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = createdInvite.code,
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                    fontWeight = FontWeight.Bold,
                                )
                                TextButton(
                                    onClick = { onCopyInviteCode(createdInvite.code) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.onboarding_invite_copy_code))
                                }
                            }
                            Text(
                                text = stringResource(R.string.onboarding_invite_meta),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = onShareInviteAgain,
                                shape = CircleShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.onboarding_invite_share_again))
                            }
                        }
                    }
                }
            }
        } else {
            // Mode 1: Create new family
            val createSelected = state.familyMode == OnboardingFamilyMode.CREATE
            OutlinedCard(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(if (createSelected) 1.5.dp else 1.dp, if (createSelected) colors.primaryContainer else stitch.hairline),
                colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.circleBusy) { onSelectMode(OnboardingFamilyMode.CREATE) },
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(
                            selected = createSelected,
                            onClick = { onSelectMode(OnboardingFamilyMode.CREATE) },
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.onboarding_family_create_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.onboarding_family_create_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    if (createSelected) {
                        OutlinedTextField(
                            value = state.circleNameInput,
                            onValueChange = onCircleNameChange,
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            label = { Text(stringResource(R.string.onboarding_family_name_label)) },
                            enabled = !state.circleBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding-circle-name"),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            suggestions.forEach { preset ->
                                FilterChip(
                                    selected = state.circleNameInput.trim() == preset,
                                    onClick = { onCircleNameChange(preset) },
                                    label = { Text(preset) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                        state.circleErrorRes?.let { err ->
                            AnveErrorNotice(
                                text = stringResource(err),
                                onRetry = if (state.circleUnknown) onCheckCircleAgain else onCreateCircle,
                                embedded = true,
                            )
                        }
                        Button(
                            onClick = if (state.circleUnknown) onCheckCircleAgain else onCreateCircle,
                            enabled = !state.circleBusy && state.online && (state.circleUnknown || state.circleNameInput.isNotBlank()),
                            shape = CircleShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("onboarding-circle-create"),
                        ) {
                            if (state.circleBusy) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                stringResource(
                                    if (state.circleUnknown) R.string.onboarding_circle_check_again else R.string.onboarding_circle_create_button,
                                ),
                            )
                        }
                    }
                }
            }

            // Mode 2: Join existing family
            val joinSelected = state.familyMode == OnboardingFamilyMode.JOIN
            OutlinedCard(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(if (joinSelected) 1.5.dp else 1.dp, if (joinSelected) colors.primaryContainer else stitch.hairline),
                colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.circleBusy) { onSelectMode(OnboardingFamilyMode.JOIN) },
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(
                            selected = joinSelected,
                            onClick = { onSelectMode(OnboardingFamilyMode.JOIN) },
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.onboarding_family_join_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.onboarding_family_join_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    if (joinSelected) {
                        OutlinedTextField(
                            value = state.joinCodeInput,
                            onValueChange = onJoinCodeChange,
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            label = { Text(stringResource(R.string.onboarding_family_join_code_label)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding-join-code"),
                        )
                        FilledTonalButton(
                            onClick = { onReviewManualInvite(state.joinCodeInput) },
                            enabled = state.joinCodeInput.isNotBlank() && state.online,
                            shape = CircleShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("onboarding-join-review"),
                        ) {
                            Text(stringResource(R.string.onboarding_family_join_button))
                        }

                        HorizontalDivider(color = stitch.hairline)
                        Text(
                            text = stringResource(R.string.onboarding_family_received_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        when {
                            state.receivedLoading -> StitchLoadingSkeletonCard(rows = 1, embedded = true)
                            state.receivedErrorRes != null -> AnveErrorNotice(
                                text = stringResource(state.receivedErrorRes),
                                onRetry = onRefreshFamily,
                                embedded = true,
                            )
                            state.receivedInvites.isEmpty() -> Text(
                                text = stringResource(R.string.onboarding_family_received_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            else -> state.receivedInvites.forEachIndexed { idx, invite ->
                                if (idx > 0) {
                                    HorizontalDivider(color = stitch.hairline)
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = invite.circleName,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = stringResource(R.string.invite_received_intro, invite.inviterDisplayName),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(
                                        onClick = { onReviewReceivedInvite(invite.id) },
                                        enabled = !state.receivedBusy && state.online,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text(stringResource(R.string.invite_view))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.circle != null) {
            Button(
                onClick = onContinue,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("onboarding-family-continue"),
            ) {
                Text(stringResource(R.string.onboarding_family_continue))
            }
            if (state.isOwner && state.createdInvite == null) {
                TextButton(
                    onClick = onSkipStep,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.onboarding_family_later))
                }
            }
        } else {
            OutlinedButton(
                onClick = onSkipStep,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("onboarding-family-skip"),
            ) {
                Text(stringResource(R.string.onboarding_family_skip))
            }
        }
    }
}

@Composable
internal fun PermissionsStepContent(
    state: OnboardingUiState,
    backgroundOptionLabel: String,
    notificationsRequired: Boolean,
    backgroundRequired: Boolean,
    onRequestNotifications: () -> Unit,
    onToggleConsent: (Boolean) -> Unit,
    onRequestForegroundLocation: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onContinue: () -> Unit,
    onSkipStep: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    val notifGranted = !notificationsRequired || state.permissions.notifications
    val fgGranted = state.permissions.canTrack
    val bgGranted = !backgroundRequired || state.permissions.backgroundLocation

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = stringResource(R.string.onboarding_permissions_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )

        // Prominent Disclosure card required prior to location / background requests
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_disclosure_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.onboarding_disclosure_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Card 1: Notifications
        PermissionCard(
            title = stringResource(R.string.onboarding_perm_notif_title),
            body = stringResource(R.string.onboarding_perm_notif_body),
            badgeText = stringResource(if (notifGranted) R.string.onboarding_perm_granted else R.string.onboarding_perm_pending),
            granted = notifGranted,
            actionLabel = if (!notifGranted) stringResource(R.string.onboarding_perm_notif_action) else null,
            onAction = onRequestNotifications,
        )

        // Card 2: Explicit Sharing Consent (separate from OS) + Foreground Location
        OutlinedCard(
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, if (fgGranted && state.consentEnabled) stitch.hairline else colors.primaryContainer),
            colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_perm_fg_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    StitchSemanticBadge(
                        text = stringResource(
                            when (state.permissions.location) {
                                LocationPermissionState.GRANTED_PRECISE -> R.string.onboarding_perm_granted
                                LocationPermissionState.GRANTED_APPROXIMATE -> R.string.onboarding_perm_approx
                                LocationPermissionState.DENIED,
                                LocationPermissionState.NOT_REQUESTED,
                                -> R.string.onboarding_perm_pending
                            },
                        ),
                        kind = if (fgGranted) StitchBadgeKind.SAFE_HOME else StitchBadgeKind.NEUTRAL,
                    )
                }
                Text(
                    text = stringResource(R.string.onboarding_perm_fg_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                HorizontalDivider(color = stitch.hairline)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = state.consentEnabled,
                            enabled = !state.consentBusy,
                            role = Role.Switch,
                            onValueChange = onToggleConsent,
                        )
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.onboarding_perm_consent_label),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.onboarding_perm_consent_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.consentEnabled,
                        onCheckedChange = null,
                        enabled = !state.consentBusy,
                    )
                }
                state.consentNotice?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (!fgGranted) {
                    FilledTonalButton(
                        onClick = onRequestForegroundLocation,
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("onboarding-perm-fg-btn"),
                    ) {
                        Text(stringResource(R.string.onboarding_perm_fg_action))
                    }
                }
            }
        }

        // Card 3: Background Location
        if (backgroundRequired) {
            PermissionCard(
                title = stringResource(R.string.onboarding_perm_bg_title),
                body = stringResource(R.string.onboarding_perm_bg_body, backgroundOptionLabel),
                badgeText = stringResource(if (bgGranted) R.string.onboarding_perm_granted else R.string.onboarding_perm_pending),
                granted = bgGranted,
                actionLabel = if (!bgGranted) stringResource(R.string.onboarding_perm_bg_action) else null,
                onAction = onRequestBackgroundLocation,
            )
        }

        val (primaryLabelRes, primaryAction) = when {
            !notifGranted -> R.string.onboarding_perm_notif_action to onRequestNotifications
            !fgGranted -> R.string.onboarding_perm_fg_action to onRequestForegroundLocation
            backgroundRequired && !bgGranted -> R.string.onboarding_perm_bg_action to onRequestBackgroundLocation
            else -> R.string.onboarding_perm_continue to onContinue
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = primaryAction,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("onboarding-perm-primary"),
        ) {
            Text(stringResource(primaryLabelRes))
        }
        TextButton(
            onClick = onSkipStep,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("onboarding-perm-skip"),
        ) {
            Text(stringResource(R.string.onboarding_perm_later))
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    body: String,
    badgeText: String,
    granted: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    OutlinedCard(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (granted) stitch.hairline else colors.primaryContainer),
        colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                StitchSemanticBadge(
                    text = badgeText,
                    kind = if (granted) StitchBadgeKind.SAFE_HOME else StitchBadgeKind.NEUTRAL,
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            if (actionLabel != null) {
                FilledTonalButton(
                    onClick = onAction,
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AudienceAndPlaceStepContent(
    state: OnboardingUiState,
    searchQuery: String,
    searching: Boolean,
    searchOutcome: PlaceSearchOutcome?,
    locatingCurrentFix: Boolean,
    locationFixErrorRes: Int?,
    onSearchQueryChange: (String) -> Unit,
    onSelectSearchSuggestion: (PlaceSuggestion) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onToggleViewerGrant: (String, Boolean) -> Unit,
    onRetryAudience: () -> Unit,
    onPlaceNameChange: (String) -> Unit,
    onPlaceCoordinatesChange: (String, String) -> Unit,
    onPlaceRadiusChange: (Double) -> Unit,
    onToggleSharedWithCircle: (Boolean) -> Unit,
    onTogglePlaceMonitor: (Boolean) -> Unit,
    onTogglePlaceRecipient: (String) -> Unit,
    onSavePlaceAndContinue: () -> Unit,
    onSkipPlaceAndContinue: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current
    var showManualCoords by rememberSaveable { mutableStateOf(false) }

    val placePresets = listOf(
        stringResource(R.string.places_preset_home),
        stringResource(R.string.places_preset_work),
        stringResource(R.string.places_preset_school),
    )
    val radiusOptions = listOf(100.0, 150.0, 300.0, 500.0)
    val grantedViewers = state.otherMembers.filter { it.canViewLocation }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.onboarding_step4_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )

        // Section 1: Who can view your location
        OutlinedCard(
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, stitch.hairline),
            colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AnVeSectionHeader(
                    title = stringResource(R.string.onboarding_audience_title),
                )
                when {
                    state.audienceLoading -> StitchLoadingSkeletonCard(rows = 2, embedded = true)
                    state.audienceErrorRes != null -> AnveErrorNotice(
                        text = stringResource(state.audienceErrorRes),
                        onRetry = onRetryAudience,
                        embedded = true,
                    )
                    state.otherMembers.isEmpty() -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.onboarding_audience_waiting),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                            StitchSemanticBadge(
                                text = stringResource(R.string.onboarding_audience_default_off),
                                kind = StitchBadgeKind.NEUTRAL,
                            )
                        }
                    }
                    else -> {
                        if (!state.consentEnabled) {
                            Text(
                                text = stringResource(R.string.onboarding_audience_need_consent),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        state.audienceMessage?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error,
                            )
                        }
                        state.otherMembers.forEach { member ->
                            val updating = member.userId in state.audienceUpdatingIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .toggleable(
                                        value = member.canViewLocation,
                                        enabled = state.consentEnabled && !updating && state.online,
                                        role = Role.Switch,
                                        onValueChange = { onToggleViewerGrant(member.userId, it) },
                                    )
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = member.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                if (updating) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Switch(
                                        checked = member.canViewLocation,
                                        onCheckedChange = null,
                                        enabled = state.consentEnabled && state.online,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 2: First Familiar Place
        OutlinedCard(
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, stitch.hairline),
            colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnVeSectionHeader(
                    title = stringResource(R.string.onboarding_place_title),
                )
                if (state.circle == null) {
                    Text(
                        text = stringResource(R.string.onboarding_place_need_circle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        placePresets.forEach { preset ->
                            FilterChip(
                                selected = state.placeName.trim() == preset,
                                onClick = { onPlaceNameChange(preset) },
                                label = { Text(preset) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = state.placeName,
                        onValueChange = onPlaceNameChange,
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        label = { Text(stringResource(R.string.onboarding_place_name_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding-place-name"),
                    )
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        label = { Text(stringResource(R.string.places_search_label)) },
                        placeholder = { Text(stringResource(R.string.places_search_hint)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChange("") }) {
                                    Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.places_search_clear))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.places_search_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    if (searching) {
                        Text(
                            text = stringResource(R.string.places_search_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    } else if (searchOutcome is PlaceSearchOutcome.Found) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            searchOutcome.suggestions.forEach { suggestion ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = colors.surfaceContainerLow,
                                    border = BorderStroke(1.dp, stitch.hairline),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectSearchSuggestion(suggestion) },
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(suggestion.title, style = MaterialTheme.typography.titleSmall)
                                        if (suggestion.subtitle.isNotBlank()) {
                                            Text(
                                                suggestion.subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else if (searchOutcome is PlaceSearchOutcome.NoResult) {
                        Text(stringResource(R.string.places_search_none), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    } else if (searchOutcome is PlaceSearchOutcome.Failed) {
                        Text(stringResource(R.string.places_search_failed), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    } else if (searchOutcome is PlaceSearchOutcome.Unavailable) {
                        Text(stringResource(R.string.places_search_unavailable), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }

                    OutlinedButton(
                        onClick = onUseCurrentLocation,
                        enabled = !locatingCurrentFix,
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        if (locatingCurrentFix) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.onboarding_place_locating))
                        } else {
                            Icon(Icons.Outlined.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.onboarding_place_use_current))
                        }
                    }
                    locationFixErrorRes?.let { errRes ->
                        Text(
                            text = stringResource(errRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error,
                        )
                    }

                    TextButton(
                        onClick = { showManualCoords = !showManualCoords },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.places_manual_coords_toggle))
                    }
                    if (showManualCoords) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.placeLatitude,
                                onValueChange = { onPlaceCoordinatesChange(it, state.placeLongitude) },
                                singleLine = true,
                                label = { Text(stringResource(R.string.places_editor_lat_label)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = state.placeLongitude,
                                onValueChange = { onPlaceCoordinatesChange(state.placeLatitude, it) },
                                singleLine = true,
                                label = { Text(stringResource(R.string.places_editor_lon_label)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.onboarding_place_radius_label, state.placeRadiusMeters.toInt()),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        radiusOptions.forEach { radius ->
                            FilterChip(
                                selected = state.placeRadiusMeters == radius,
                                onClick = { onPlaceRadiusChange(radius) },
                                label = { Text("${radius.toInt()}m") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = state.placeSharedWithCircle,
                                role = Role.Switch,
                                onValueChange = onToggleSharedWithCircle,
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_place_shared_label),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = state.placeSharedWithCircle, onCheckedChange = null)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = state.placeMonitorEnabled,
                                role = Role.Switch,
                                onValueChange = onTogglePlaceMonitor,
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_place_monitor_label),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = state.placeMonitorEnabled, onCheckedChange = null)
                    }

                    if (state.placeMonitorEnabled) {
                        if (grantedViewers.isEmpty()) {
                            Text(
                                text = stringResource(R.string.onboarding_place_monitor_need_viewer),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        } else {
                            grantedViewers.forEach { viewer ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clickable { onTogglePlaceRecipient(viewer.userId) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = viewer.userId in state.selectedPlaceRecipients,
                                        onCheckedChange = null,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(viewer.displayName, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }

                    state.placeErrorRes?.let { err ->
                        AnveErrorNotice(text = stringResource(err), embedded = true)
                    }
                }
            }
        }

        if (state.circle != null) {
            Button(
                onClick = onSavePlaceAndContinue,
                enabled = !state.placeBusy && state.online,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("onboarding-place-save"),
            ) {
                if (state.placeBusy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.onboarding_place_save_continue))
            }
            OutlinedButton(
                onClick = onSkipPlaceAndContinue,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("onboarding-place-skip"),
            ) {
                Text(stringResource(R.string.onboarding_place_skip))
            }
        } else {
            Button(
                onClick = onSkipPlaceAndContinue,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("onboarding-place-skip"),
            ) {
                Text(stringResource(R.string.onboarding_perm_continue))
            }
        }
    }
}

@Composable
internal fun CompleteStepContent(
    state: OnboardingUiState,
    backgroundRequired: Boolean,
    onEnterApp: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val stitch = LocalStitchColors.current

    val circleSummary = when {
        state.circle != null && state.createdInvite != null ->
            "${state.circle.name} (${stringResource(R.string.onboarding_done_summary_invite)})"
        state.circle != null -> state.circle.name
        else -> stringResource(R.string.onboarding_done_summary_no_circle)
    }

    val permSummary = when {
        state.permissions.canTrack && (!backgroundRequired || state.permissions.backgroundLocation) ->
            stringResource(R.string.onboarding_done_loc_bg)
        state.permissions.canTrack ->
            stringResource(R.string.onboarding_done_loc_fg)
        else ->
            stringResource(R.string.onboarding_done_loc_none)
    }
    val consentSummary = stringResource(
        if (state.consentEnabled) R.string.onboarding_done_consent_on else R.string.onboarding_done_consent_off,
    )
    val placeSummary = state.createdPlace?.let { "${it.name} (${it.radiusMeters.toInt()}m)" }
        ?: stringResource(R.string.onboarding_done_place_none)

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnVeHomeEmblem(Modifier.size(88.dp))
        }

        Text(
            text = stringResource(R.string.onboarding_done_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )

        OutlinedCard(
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, stitch.hairline),
            colors = CardDefaults.outlinedCardColors(containerColor = stitch.cardTactile),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SummaryRow(
                    label = stringResource(R.string.onboarding_done_summary_circle),
                    value = circleSummary,
                )
                HorizontalDivider(color = stitch.hairline)
                SummaryRow(
                    label = stringResource(R.string.onboarding_done_summary_location),
                    value = "$permSummary · $consentSummary",
                )
                HorizontalDivider(color = stitch.hairline)
                SummaryRow(
                    label = stringResource(R.string.onboarding_done_summary_place),
                    value = placeSummary,
                )
            }
        }

        Text(
            text = stringResource(R.string.onboarding_done_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )

        Button(
            onClick = onEnterApp,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("onboarding-complete-enter"),
        ) {
            Text(stringResource(R.string.onboarding_done_enter_app))
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
