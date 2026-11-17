package com.anve.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.anve.app.R
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.CircleDto
import com.anve.app.data.api.CircleMembershipDto
import com.anve.app.data.api.InviteDto
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.api.PlaceInputDto
import com.anve.app.data.api.PrivateInvitationRequestDto
import com.anve.app.data.api.ReceivedInvitationDto
import com.anve.app.data.auth.SessionStorage
import com.anve.app.data.circles.CircleRepository
import com.anve.app.data.circles.InviteLink
import com.anve.app.data.circles.PrivateInvitationGateway
import com.anve.app.data.location.AudienceMember
import com.anve.app.data.location.AudienceMutationOutcome
import com.anve.app.data.location.PlacesDataSource
import com.anve.app.data.location.SharingManagementDataSource
import com.anve.app.data.onboarding.OnboardingStatus
import com.anve.app.data.onboarding.OnboardingStorage
import com.anve.app.tracking.ConsentController
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.tracking.PermissionSnapshot
import com.anve.app.tracking.TrackingControl
import com.anve.app.tracking.TrackingPermissions
import com.anve.app.ui.places.validPlaceInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class OnboardingStep {
    WELCOME,
    FAMILY,
    PERMISSIONS,
    AUDIENCE_AND_PLACE,
    COMPLETE,
}

enum class OnboardingFamilyMode {
    CREATE,
    JOIN,
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val familyMode: OnboardingFamilyMode = OnboardingFamilyMode.CREATE,
    val online: Boolean = true,

    // Step 2: Family & Link Invitation
    val loadingFamily: Boolean = false,
    val circle: CircleDto? = null,
    val isOwner: Boolean = false,
    val circleNameInput: String = "",
    val circleBusy: Boolean = false,
    val circleUnknown: Boolean = false,
    val circleErrorRes: Int? = null,
    val createdInvite: InviteDto? = null,
    val inviteBusy: Boolean = false,
    val inviteUnknown: Boolean = false,
    val inviteErrorRes: Int? = null,
    val pendingShareInvite: InviteDto? = null,
    val joinCodeInput: String = "",
    val receivedInvites: List<ReceivedInvitationDto> = emptyList(),
    val receivedLoading: Boolean = false,
    val receivedBusy: Boolean = false,
    val receivedErrorRes: Int? = null,
    val pendingReviewCode: String? = null,

    // Step 3: Permissions & Consent (consent defaults OFF, separate from OS permissions)
    val permissions: PermissionSnapshot = PermissionSnapshot(
        location = LocationPermissionState.DENIED,
        backgroundLocation = false,
        notifications = false,
        locationServicesEnabled = false,
    ),
    val consentEnabled: Boolean = false,
    val consentBusy: Boolean = false,
    val consentPending: Boolean = false,
    val consentNotice: String? = null,

    // Step 4: Audience & First Place
    val audienceLoading: Boolean = false,
    val audienceErrorRes: Int? = null,
    val otherMembers: List<AudienceMember> = emptyList(),
    val audienceUpdatingIds: Set<String> = emptySet(),
    val audienceMessage: String? = null,
    val placeName: String = "",
    val placeLatitude: String = "",
    val placeLongitude: String = "",
    val placeRadiusMeters: Double = 150.0,
    val placeSharedWithCircle: Boolean = true,
    val placeMonitorEnabled: Boolean = false,
    val selectedPlaceRecipients: Set<String> = emptySet(),
    val placeBusy: Boolean = false,
    val placeErrorRes: Int? = null,
    val createdPlace: PlaceDto? = null,
) {
    val stepNumber: Int get() = step.ordinal + 1
    val stepCount: Int get() = OnboardingStep.entries.size
}

/**
 * Coordinates the 5-step first-run onboarding state for one authenticated
 * account (`owner`). Reuses existing Circle, Invitation, Tracking, and Places
 * repositories without introducing separate onboarding API endpoints.
 */
class OnboardingViewModel(
    private val owner: String,
    private val sessions: SessionStorage,
    private val onboardingStore: OnboardingStorage,
    private val circleRepo: CircleRepository,
    private val invitationGateway: PrivateInvitationGateway,
    private val trackingControl: TrackingControl,
    private val trackingPermissions: TrackingPermissions,
    private val sharingRepo: SharingManagementDataSource,
    private val placesRepo: PlacesDataSource,
    startWithPendingInvite: Boolean = false,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val ownedScope = externalScope == null
    private val scope: CoroutineScope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(
        OnboardingUiState(
            step = if (startWithPendingInvite) OnboardingStep.FAMILY else OnboardingStep.WELCOME,
            familyMode = if (startWithPendingInvite) OnboardingFamilyMode.JOIN else OnboardingFamilyMode.CREATE,
            permissions = trackingPermissions.snapshot(),
            consentEnabled = trackingControl.status.value.consentEnabled,
            online = trackingControl.status.value.online,
        ),
    )
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private var pendingInviteRequestId: String? = null
    private var pendingInviteCircleId: String? = null
    private var generation = 0L

    init {
        scope.launch {
            trackingControl.status.collect { status ->
                _state.update {
                    it.copy(
                        online = status.online,
                        consentEnabled = status.consentEnabled,
                        consentPending = status.consentSyncPending,
                    )
                }
            }
        }
        scope.launch {
            sessions.session.collect { session ->
                if (session?.userId != owner) {
                    generation++
                    pendingInviteRequestId = null
                    pendingInviteCircleId = null
                }
            }
        }
        refreshFamilyState()
    }

    override fun onCleared() {
        if (ownedScope) scope.cancel()
        super.onCleared()
    }

    private suspend fun isCurrentOwner(expectedGen: Long = generation): Boolean =
        generation == expectedGen && sessions.current()?.userId == owner

    fun refreshPermissions() {
        trackingControl.refreshStatus()
        _state.update {
            it.copy(
                permissions = trackingPermissions.snapshot(),
                consentEnabled = trackingControl.status.value.consentEnabled,
                online = trackingControl.status.value.online,
            )
        }
    }

    fun startGuidedSetup() {
        _state.update { it.copy(step = OnboardingStep.FAMILY) }
        refreshFamilyState()
    }

    fun skipAllToHome(onDone: () -> Unit) {
        onboardingStore.setStatus(owner, OnboardingStatus.SKIPPED)
        onDone()
    }

    fun completeToHome(onDone: () -> Unit) {
        onboardingStore.setStatus(owner, OnboardingStatus.COMPLETED)
        onDone()
    }

    fun navigateBack(onExitRequested: () -> Unit) {
        when (_state.value.step) {
            OnboardingStep.WELCOME -> onExitRequested()
            OnboardingStep.FAMILY -> _state.update { it.copy(step = OnboardingStep.WELCOME) }
            OnboardingStep.PERMISSIONS -> _state.update { it.copy(step = OnboardingStep.FAMILY) }
            OnboardingStep.AUDIENCE_AND_PLACE -> _state.update { it.copy(step = OnboardingStep.PERMISSIONS) }
            OnboardingStep.COMPLETE -> _state.update { it.copy(step = OnboardingStep.AUDIENCE_AND_PLACE) }
        }
    }

    // ── Step 2: Family & Invite ──────────────────────────────────────────────

    fun selectFamilyMode(mode: OnboardingFamilyMode) {
        if (_state.value.circleBusy || _state.value.inviteBusy) return
        _state.update { it.copy(familyMode = mode, circleErrorRes = null, inviteErrorRes = null) }
    }

    fun updateCircleName(value: String) {
        if (_state.value.circleBusy) return
        _state.update { it.copy(circleNameInput = value.take(60), circleErrorRes = null) }
    }

    fun updateJoinCode(value: String) {
        _state.update { it.copy(joinCodeInput = value.take(256), circleErrorRes = null) }
    }

    fun refreshFamilyState() {
        val token = generation
        scope.launch {
            _state.update { it.copy(loadingFamily = true, receivedLoading = true, circleErrorRes = null, receivedErrorRes = null) }
            try {
                val mine = circleRepo.myCircle(owner)
                if (!isCurrentOwner(token)) return@launch
                applyCircleSnapshot(mine)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isCurrentOwner(token)) {
                    _state.update { it.copy(loadingFamily = false) }
                }
            }
            try {
                val received = invitationGateway.received(owner)
                if (!isCurrentOwner(token)) return@launch
                _state.update { it.copy(receivedLoading = false, receivedInvites = received, receivedErrorRes = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isCurrentOwner(token)) {
                    _state.update { it.copy(receivedLoading = false, receivedErrorRes = R.string.invite_received_load_failed) }
                }
            }
        }
    }

    fun createCircle() {
        val current = _state.value
        if (current.circleBusy || current.circle != null) return
        val name = current.circleNameInput.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(circleErrorRes = R.string.onboarding_circle_name_required) }
            return
        }
        if (!current.online) {
            _state.update { it.copy(circleErrorRes = R.string.onboarding_offline_notice) }
            return
        }
        val token = generation
        scope.launch {
            _state.update { it.copy(circleBusy = true, circleUnknown = false, circleErrorRes = null) }
            try {
                val created = circleRepo.create(name)
                if (!isCurrentOwner(token)) return@launch
                applyCircleSnapshot(created)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrentOwner(token)) return@launch
                if (e is ApiException && e.httpStatus in 400..499 && e.httpStatus != 408) {
                    val alreadyExists = e.code == "ALREADY_IN_CIRCLE" || e.httpStatus == 409
                    if (alreadyExists) {
                        reconcileCircleReadback(token)
                    } else {
                        _state.update { it.copy(circleBusy = false, circleErrorRes = R.string.onboarding_circle_create_failed) }
                    }
                } else {
                    reconcileCircleReadback(token)
                }
            }
        }
    }

    fun checkCircleAgain() {
        if (_state.value.circleBusy || !_state.value.online) return
        val token = generation
        scope.launch {
            _state.update { it.copy(circleBusy = true, circleErrorRes = null) }
            reconcileCircleReadback(token)
        }
    }

    private suspend fun reconcileCircleReadback(token: Long) {
        try {
            val mine = circleRepo.myCircle(owner)
            if (!isCurrentOwner(token)) return
            if (mine.circle != null) {
                applyCircleSnapshot(mine)
            } else {
                _state.update {
                    it.copy(
                        circleBusy = false,
                        circleUnknown = true,
                        circleErrorRes = R.string.onboarding_circle_unconfirmed,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (isCurrentOwner(token)) {
                _state.update {
                    it.copy(
                        circleBusy = false,
                        circleUnknown = true,
                        circleErrorRes = R.string.onboarding_circle_unconfirmed,
                    )
                }
            }
        }
    }

    fun onJoinedCircle(joined: CircleMembershipDto?) {
        if (joined != null && joined.membership?.userId == owner && joined.circle != null) {
            applyCircleSnapshot(joined)
        } else {
            refreshFamilyState()
        }
    }

    private fun applyCircleSnapshot(membership: CircleMembershipDto) {
        _state.update {
            it.copy(
                loadingFamily = false,
                circleBusy = false,
                circleUnknown = false,
                circle = membership.circle,
                isOwner = membership.membership?.role == "owner",
                circleErrorRes = null,
            )
        }
    }

    /**
     * Creates a single-use LINK invitation valid for 7 days (168h). Keeps a
     * fixed requestId across unknown network outcomes so readback never creates
     * duplicate invitations.
     */
    fun createAndShareInviteLink() {
        val current = _state.value
        val circle = current.circle ?: return
        if (!current.isOwner || current.inviteBusy) return
        if (current.inviteUnknown) {
            checkInviteAgain()
            return
        }
        if (!current.online) {
            _state.update { it.copy(inviteErrorRes = R.string.invite_create_offline) }
            return
        }
        val requestId = UUID.randomUUID().toString()
        pendingInviteRequestId = requestId
        pendingInviteCircleId = circle.id
        val request = PrivateInvitationRequestDto(
            requestId = requestId,
            mode = "LINK",
            recipientEmail = null,
            expiresInHours = INVITE_HOURS_SEVEN_DAYS,
        )
        val token = generation
        scope.launch {
            _state.update { it.copy(inviteBusy = true, inviteUnknown = false, inviteErrorRes = null) }
            try {
                val created = invitationGateway.create(owner, circle.id, request)
                if (!isCurrentOwner(token)) return@launch
                if (InviteLink.create(created.code) != null) {
                    applyCreatedInvite(created)
                } else {
                    reconcileInviteReadback(circle.id, requestId, token)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrentOwner(token)) return@launch
                if (e is ApiException && e.httpStatus in 400..499 && e.httpStatus != 408) {
                    pendingInviteRequestId = null
                    pendingInviteCircleId = null
                    _state.update {
                        it.copy(
                            inviteBusy = false,
                            inviteErrorRes = if (e.httpStatus == 429) R.string.invite_create_rate_limited else R.string.onboarding_invite_create_failed,
                        )
                    }
                } else {
                    reconcileInviteReadback(circle.id, requestId, token)
                }
            }
        }
    }

    fun checkInviteAgain() {
        val requestId = pendingInviteRequestId ?: return
        val circleId = pendingInviteCircleId ?: return
        val current = _state.value
        if (current.inviteBusy || !current.online) return
        val token = generation
        scope.launch {
            _state.update { it.copy(inviteBusy = true, inviteErrorRes = null) }
            reconcileInviteReadback(circleId, requestId, token)
        }
    }

    private suspend fun reconcileInviteReadback(circleId: String, requestId: String, token: Long) {
        try {
            val created = invitationGateway.created(owner, circleId, requestId)
            if (!isCurrentOwner(token)) return
            if (created.id == requestId && InviteLink.create(created.code) != null) {
                applyCreatedInvite(created)
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        if (isCurrentOwner(token)) {
            _state.update {
                it.copy(
                    inviteBusy = false,
                    inviteUnknown = true,
                    inviteErrorRes = R.string.invite_create_unconfirmed,
                )
            }
        }
    }

    private fun applyCreatedInvite(invite: InviteDto) {
        pendingInviteRequestId = null
        pendingInviteCircleId = null
        _state.update {
            it.copy(
                inviteBusy = false,
                inviteUnknown = false,
                inviteErrorRes = null,
                createdInvite = invite,
                pendingShareInvite = invite,
            )
        }
    }

    fun requestShareAgain() {
        val invite = _state.value.createdInvite ?: return
        _state.update { it.copy(pendingShareInvite = invite) }
    }

    fun consumeShareRequest() {
        _state.update { it.copy(pendingShareInvite = null) }
    }

    fun openReceivedInvitation(inviteId: String) {
        if (_state.value.receivedBusy) return
        val token = generation
        scope.launch {
            _state.update { it.copy(receivedBusy = true, receivedErrorRes = null) }
            try {
                val detail = invitationGateway.detail(owner, inviteId)
                if (!isCurrentOwner(token)) return@launch
                val code = detail.code?.takeIf { InviteLink.create(it) != null }
                if (code == null) {
                    _state.update { it.copy(receivedBusy = false, receivedErrorRes = R.string.invite_open_failed) }
                } else {
                    _state.update { it.copy(receivedBusy = false, pendingReviewCode = code) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isCurrentOwner(token)) {
                    _state.update { it.copy(receivedBusy = false, receivedErrorRes = R.string.invite_open_failed) }
                }
            }
        }
    }

    fun consumeReviewCode() {
        _state.update { it.copy(pendingReviewCode = null) }
    }

    fun proceedFromFamily() {
        refreshPermissions()
        _state.update { it.copy(step = OnboardingStep.PERMISSIONS) }
    }

    // ── Step 3: Permissions & Consent ────────────────────────────────────────

    fun setConsent(enabled: Boolean) {
        if (_state.value.consentBusy) return
        val token = generation
        scope.launch {
            _state.update { it.copy(consentBusy = true, consentNotice = null) }
            val result = (trackingControl as ConsentController).setConsent(enabled)
            if (!isCurrentOwner(token)) return@launch
            _state.update {
                it.copy(
                    consentBusy = false,
                    consentEnabled = result.enabled,
                    consentPending = !result.serverConfirmed,
                    consentNotice = result.message,
                )
            }
        }
    }

    fun proceedFromPermissions() {
        _state.update { it.copy(step = OnboardingStep.AUDIENCE_AND_PLACE) }
        loadAudienceSnapshot()
    }

    // ── Step 4: Audience & First Place ───────────────────────────────────────

    fun loadAudienceSnapshot() {
        val token = generation
        scope.launch {
            _state.update { it.copy(audienceLoading = true, audienceErrorRes = null) }
            try {
                val snapshot = sharingRepo.sharingManagement(owner)
                if (!isCurrentOwner(token)) return@launch
                val others = snapshot.members.filter { it.userId != owner }
                _state.update {
                    it.copy(
                        audienceLoading = false,
                        audienceErrorRes = null,
                        consentEnabled = snapshot.consentEnabled,
                        otherMembers = others,
                        selectedPlaceRecipients = it.selectedPlaceRecipients.intersect(
                            others.filter { member -> member.canViewLocation }.mapTo(mutableSetOf()) { member -> member.userId },
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isCurrentOwner(token)) {
                    _state.update {
                        it.copy(
                            audienceLoading = false,
                            audienceErrorRes = R.string.onboarding_audience_load_failed,
                        )
                    }
                }
            }
        }
    }

    fun toggleViewerGrant(viewerId: String, enabled: Boolean) {
        if (viewerId in _state.value.audienceUpdatingIds) return
        val token = generation
        scope.launch {
            _state.update {
                it.copy(
                    audienceUpdatingIds = it.audienceUpdatingIds + viewerId,
                    audienceMessage = null,
                )
            }
            val outcome = sharingRepo.setViewerGrant(owner, viewerId, enabled)
            if (!isCurrentOwner(token)) return@launch
            when (outcome) {
                is AudienceMutationOutcome.Confirmed -> {
                    val others = outcome.snapshot?.members?.filter { it.userId != owner } ?: _state.value.otherMembers
                    _state.update {
                        it.copy(
                            audienceUpdatingIds = it.audienceUpdatingIds - viewerId,
                            otherMembers = others,
                            consentEnabled = outcome.snapshot?.consentEnabled ?: it.consentEnabled,
                            selectedPlaceRecipients = if (!enabled) it.selectedPlaceRecipients - viewerId else it.selectedPlaceRecipients,
                        )
                    }
                }
                is AudienceMutationOutcome.Rejected -> {
                    _state.update {
                        it.copy(
                            audienceUpdatingIds = it.audienceUpdatingIds - viewerId,
                            otherMembers = outcome.snapshot?.members?.filter { m -> m.userId != owner } ?: it.otherMembers,
                            audienceMessage = outcome.message,
                        )
                    }
                }
                is AudienceMutationOutcome.UnknownOutcome -> {
                    _state.update {
                        it.copy(
                            audienceUpdatingIds = it.audienceUpdatingIds - viewerId,
                            otherMembers = outcome.snapshot?.members?.filter { m -> m.userId != owner } ?: it.otherMembers,
                            audienceMessage = "Chưa xác nhận được thay đổi quyền xem. Vui lòng kiểm tra lại.",
                        )
                    }
                }
            }
        }
    }

    fun updatePlaceName(name: String) {
        _state.update { it.copy(placeName = name.take(80), placeErrorRes = null) }
    }

    fun updatePlaceCoordinates(lat: String, lng: String) {
        _state.update { it.copy(placeLatitude = lat, placeLongitude = lng, placeErrorRes = null) }
    }

    fun updatePlaceRadius(radiusMeters: Double) {
        _state.update { it.copy(placeRadiusMeters = radiusMeters, placeErrorRes = null) }
    }

    fun togglePlaceSharedWithCircle(shared: Boolean) {
        _state.update { it.copy(placeSharedWithCircle = shared) }
    }

    fun togglePlaceMonitor(enabled: Boolean) {
        _state.update { it.copy(placeMonitorEnabled = enabled) }
    }

    fun togglePlaceRecipient(memberId: String) {
        _state.update {
            val next = if (memberId in it.selectedPlaceRecipients) {
                it.selectedPlaceRecipients - memberId
            } else {
                it.selectedPlaceRecipients + memberId
            }
            it.copy(selectedPlaceRecipients = next)
        }
    }

    fun savePlaceAndContinue() {
        val current = _state.value
        if (current.placeBusy || current.circle == null) return
        if (!current.online) {
            _state.update { it.copy(placeErrorRes = R.string.onboarding_offline_notice) }
            return
        }
        val lat = current.placeLatitude.trim().toDoubleOrNull()
        val lng = current.placeLongitude.trim().toDoubleOrNull()
        if (!validPlaceInput(current.placeName, lat, lng, current.placeRadiusMeters)) {
            _state.update { it.copy(placeErrorRes = R.string.onboarding_place_invalid) }
            return
        }
        val token = generation
        scope.launch {
            _state.update { it.copy(placeBusy = true, placeErrorRes = null) }
            try {
                val created = placesRepo.create(
                    owner,
                    PlaceInputDto(
                        name = current.placeName.trim(),
                        latitude = lat!!,
                        longitude = lng!!,
                        radiusMeters = current.placeRadiusMeters,
                        sharedWithCircle = current.placeSharedWithCircle,
                    ),
                )
                if (!isCurrentOwner(token)) return@launch
                if (current.placeMonitorEnabled && current.selectedPlaceRecipients.isNotEmpty()) {
                    if (!current.placeSharedWithCircle) {
                        for (recipientId in current.selectedPlaceRecipients) {
                            runCatching { placesRepo.share(owner, created.id, recipientId, true) }
                        }
                    }
                    runCatching {
                        placesRepo.configure(owner, created.id, current.selectedPlaceRecipients.toList())
                    }
                }
                if (!isCurrentOwner(token)) return@launch
                _state.update {
                    it.copy(
                        placeBusy = false,
                        createdPlace = created,
                        step = OnboardingStep.COMPLETE,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isCurrentOwner(token)) {
                    _state.update { it.copy(placeBusy = false, placeErrorRes = R.string.onboarding_place_save_failed) }
                }
            }
        }
    }

    fun skipPlaceAndContinue() {
        _state.update { it.copy(step = OnboardingStep.COMPLETE, placeErrorRes = null) }
    }

    companion object {
        const val INVITE_HOURS_SEVEN_DAYS = 168

        fun factory(
            owner: String,
            sessions: SessionStorage,
            onboardingStore: OnboardingStorage,
            circleRepo: CircleRepository,
            invitationGateway: PrivateInvitationGateway,
            trackingControl: TrackingControl,
            trackingPermissions: TrackingPermissions,
            sharingRepo: SharingManagementDataSource,
            placesRepo: PlacesDataSource,
            startWithPendingInvite: Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = OnboardingViewModel(
                owner = owner,
                sessions = sessions,
                onboardingStore = onboardingStore,
                circleRepo = circleRepo,
                invitationGateway = invitationGateway,
                trackingControl = trackingControl,
                trackingPermissions = trackingPermissions,
                sharingRepo = sharingRepo,
                placesRepo = placesRepo,
                startWithPendingInvite = startWithPendingInvite,
            ) as T
        }
    }
}
