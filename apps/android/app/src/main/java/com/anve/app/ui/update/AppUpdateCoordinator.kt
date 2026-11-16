package com.anve.app.ui.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.StringRes
import com.anve.app.R
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.ActivityResult as PlayUpdateActivityResult
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class PlayUpdateAvailability {
    NOT_AVAILABLE,
    AVAILABLE,
    IN_PROGRESS,
}

data class PlayUpdateCheckResult(
    val availability: PlayUpdateAvailability,
    val immediateAllowed: Boolean = false,
    val availableVersionCode: Int? = null,
)

sealed interface PlayUpdateLaunchResult {
    data object Launched : PlayUpdateLaunchResult
    data object RejectedOrUnavailable : PlayUpdateLaunchResult
    data object Failed : PlayUpdateLaunchResult
}

data class AppUpdateUiState(
    val modalVisible: Boolean = false,
    val availableVersionCode: Int? = null,
    val launchingUpdate: Boolean = false,
    val flowInProgress: Boolean = false,
    @param:StringRes val errorRes: Int? = null,
)

/**
 * Isolates Play Core's task-based update API behind plain Kotlin types so the
 * resume/confirm/result state transitions can be tested directly on the JVM.
 */
interface AppUpdateGateway {
    suspend fun checkForUpdate(): PlayUpdateCheckResult?

    fun launchImmediateUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>): PlayUpdateLaunchResult
}

class PlayAppUpdateGateway(context: Context) : AppUpdateGateway {
    private val manager = AppUpdateManagerFactory.create(context.applicationContext)
    private var latestInfo: AppUpdateInfo? = null

    override suspend fun checkForUpdate(): PlayUpdateCheckResult? {
        return try {
            val info = suspendCancellableCoroutine<AppUpdateInfo> { continuation ->
                manager.appUpdateInfo
                    .addOnSuccessListener { appUpdateInfo ->
                        if (continuation.isActive) continuation.resume(appUpdateInfo)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            }
            latestInfo = info
            val availability = when (info.updateAvailability()) {
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> PlayUpdateAvailability.IN_PROGRESS
                UpdateAvailability.UPDATE_AVAILABLE -> PlayUpdateAvailability.AVAILABLE
                else -> PlayUpdateAvailability.NOT_AVAILABLE
            }
            PlayUpdateCheckResult(
                availability = availability,
                immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
                availableVersionCode = info.availableVersionCode().takeIf { it > 0 },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Debug/sideloaded builds and offline devices fail the Play check; fail open
            // so the user is never locked out when Play Store metadata is unreachable.
            null
        }
    }

    override fun launchImmediateUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>): PlayUpdateLaunchResult {
        val info = latestInfo ?: return PlayUpdateLaunchResult.RejectedOrUnavailable
        if (!info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
            return PlayUpdateLaunchResult.RejectedOrUnavailable
        }
        return try {
            val started = manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
            )
            if (started) PlayUpdateLaunchResult.Launched else PlayUpdateLaunchResult.RejectedOrUnavailable
        } catch (_: Throwable) {
            PlayUpdateLaunchResult.Failed
        }
    }
}

/**
 * Coordinates Play Store update checks across foreground transitions without
 * trapping users when Play Services is absent or offline.
 */
class AppUpdateCoordinator(
    private val scope: CoroutineScope,
    private val gateway: AppUpdateGateway,
) {
    private val _state = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private var activeCheckJob: Job? = null

    fun onResume(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        if (activeCheckJob?.isActive == true) return
        activeCheckJob = scope.launch {
            val result = gateway.checkForUpdate() ?: return@launch
            when (result.availability) {
                PlayUpdateAvailability.IN_PROGRESS -> {
                    _state.update {
                        it.copy(
                            modalVisible = true,
                            availableVersionCode = result.availableVersionCode ?: it.availableVersionCode,
                            launchingUpdate = false,
                        )
                    }
                    startImmediateFlow(launcher, treatFailureAsError = true)
                }
                PlayUpdateAvailability.AVAILABLE -> {
                    if (result.immediateAllowed) {
                        _state.update {
                            it.copy(
                                modalVisible = true,
                                availableVersionCode = result.availableVersionCode,
                                launchingUpdate = false,
                            )
                        }
                    } else {
                        _state.update { AppUpdateUiState() }
                    }
                }
                PlayUpdateAvailability.NOT_AVAILABLE -> {
                    _state.update { AppUpdateUiState() }
                }
            }
        }
    }

    fun onConfirmUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        if (_state.value.launchingUpdate || _state.value.flowInProgress) return
        startImmediateFlow(launcher, treatFailureAsError = true)
    }

    fun onUpdateFlowResult(resultCode: Int) {
        when (resultCode) {
            Activity.RESULT_OK -> {
                _state.value = AppUpdateUiState()
            }
            Activity.RESULT_CANCELED -> {
                // Keep the blocking dialog open cleanly when the user backs out of Play UI.
                _state.update {
                    it.copy(
                        modalVisible = true,
                        launchingUpdate = false,
                        flowInProgress = false,
                        errorRes = null,
                    )
                }
            }
            PlayUpdateActivityResult.RESULT_IN_APP_UPDATE_FAILED -> {
                _state.update {
                    it.copy(
                        modalVisible = true,
                        launchingUpdate = false,
                        flowInProgress = false,
                        errorRes = R.string.app_update_launch_error,
                    )
                }
            }
            else -> {
                _state.update {
                    it.copy(
                        modalVisible = true,
                        launchingUpdate = false,
                        flowInProgress = false,
                        errorRes = R.string.app_update_launch_error,
                    )
                }
            }
        }
    }

    fun onStoreUnavailable() {
        _state.update {
            it.copy(
                launchingUpdate = false,
                flowInProgress = false,
                errorRes = R.string.app_update_play_unavailable,
            )
        }
    }

    private fun startImmediateFlow(
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        treatFailureAsError: Boolean,
    ) {
        _state.update { it.copy(launchingUpdate = true, errorRes = null) }
        when (gateway.launchImmediateUpdate(launcher)) {
            PlayUpdateLaunchResult.Launched -> {
                _state.update {
                    it.copy(
                        launchingUpdate = false,
                        flowInProgress = true,
                        errorRes = null,
                    )
                }
            }
            PlayUpdateLaunchResult.RejectedOrUnavailable,
            PlayUpdateLaunchResult.Failed,
            -> {
                _state.update {
                    it.copy(
                        launchingUpdate = false,
                        flowInProgress = false,
                        errorRes = if (treatFailureAsError) R.string.app_update_launch_error else null,
                    )
                }
            }
        }
    }
}

fun openPlayStoreListing(context: Context): Boolean {
    val packageName = context.packageName
    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(marketIntent)
        true
    } catch (_: ActivityNotFoundException) {
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(webIntent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
