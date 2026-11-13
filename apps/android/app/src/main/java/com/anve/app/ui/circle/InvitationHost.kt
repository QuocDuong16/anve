package com.anve.app.ui.circle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anve.app.AppContainer
import com.anve.app.data.api.CircleMembershipDto

@Composable
fun InvitationHost(container: AppContainer, owner: String, onJoined: (CircleMembershipDto?) -> Unit) {
    val pending by container.invitationInbox.invitation.collectAsStateWithLifecycle()
    val invitation = pending?.takeIf { it.owner == owner } ?: return
    val model: InvitationViewModel = viewModel(key = "invitation-$owner-${invitation.id}", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = InvitationViewModel(owner, invitation.id, container.invitationInbox,
            container.sessionStore, RepositoryInvitationGateway(container.circleRepository, container.authRepository)) as T
    })
    val state by model.state.collectAsStateWithLifecycle()
    val tracking by container.trackingManager.status.collectAsStateWithLifecycle()
    DisposableEffect(model) { onDispose { model.deactivate() } }
    LaunchedEffect(state.joined) {
        if (state.joined) {
            container.invitationInbox.consume(invitation.id)
            onJoined(state.joinedMembership)
        }
    }
    InvitationDialog(state, tracking.online, onDismiss = {
        container.invitationInbox.consume(invitation.id)
        onJoined(null)
    }, onConfirm = model::confirm, onRetry = model::load, onCheck = model::checkAgain)
}
