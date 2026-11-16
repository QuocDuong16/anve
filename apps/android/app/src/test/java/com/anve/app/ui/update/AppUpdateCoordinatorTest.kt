package com.anve.app.ui.update

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import com.anve.app.R
import com.google.android.play.core.install.model.ActivityResult as PlayUpdateActivityResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateCoordinatorTest {
    private val fakeLauncher: ActivityResultLauncher<IntentSenderRequest> =
        object : ActivityResultLauncher<IntentSenderRequest>() {
            override fun launch(input: IntentSenderRequest, options: ActivityOptionsCompat?) = Unit

            override fun unregister() = Unit

            override val contract: ActivityResultContract<IntentSenderRequest, *>
                get() = ActivityResultContracts.StartIntentSenderForResult()
        }

    private class FakeGateway : AppUpdateGateway {
        var checkResult: PlayUpdateCheckResult? = null
        var launchResult: PlayUpdateLaunchResult = PlayUpdateLaunchResult.Launched
        var checkCalls = 0
        var launchCalls = 0

        override suspend fun checkForUpdate(): PlayUpdateCheckResult? {
            checkCalls++
            return checkResult
        }

        override fun launchImmediateUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>): PlayUpdateLaunchResult {
            launchCalls++
            return launchResult
        }
    }

    @Test
    fun notAvailableOrOfflineFailsOpenWithoutModal() = runTest {
        val gateway = FakeGateway()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val coordinator = AppUpdateCoordinator(scope, gateway)

        coordinator.onResume(fakeLauncher)
        scope.advanceUntilIdle()
        assertFalse(coordinator.state.value.modalVisible)

        gateway.checkResult = PlayUpdateCheckResult(PlayUpdateAvailability.NOT_AVAILABLE)
        coordinator.onResume(fakeLauncher)
        scope.advanceUntilIdle()
        assertFalse(coordinator.state.value.modalVisible)
    }

    @Test
    fun availableImmediateShowsModalAndLaunchFlowSucceeds() = runTest {
        val gateway = FakeGateway().apply {
            checkResult = PlayUpdateCheckResult(
                availability = PlayUpdateAvailability.AVAILABLE,
                immediateAllowed = true,
                availableVersionCode = 25,
            )
        }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val coordinator = AppUpdateCoordinator(scope, gateway)

        coordinator.onResume(fakeLauncher)
        scope.advanceUntilIdle()
        assertTrue(coordinator.state.value.modalVisible)
        assertEquals(25, coordinator.state.value.availableVersionCode)

        coordinator.onConfirmUpdate(fakeLauncher)
        assertEquals(1, gateway.launchCalls)
        assertTrue(coordinator.state.value.flowInProgress)
        assertNull(coordinator.state.value.errorRes)
    }

    @Test
    fun inProgressResumesImmediateFlowAutomatically() = runTest {
        val gateway = FakeGateway().apply {
            checkResult = PlayUpdateCheckResult(
                availability = PlayUpdateAvailability.IN_PROGRESS,
                immediateAllowed = true,
                availableVersionCode = 25,
            )
        }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val coordinator = AppUpdateCoordinator(scope, gateway)

        coordinator.onResume(fakeLauncher)
        scope.advanceUntilIdle()
        assertEquals(1, gateway.launchCalls)
        assertTrue(coordinator.state.value.flowInProgress)
    }

    @Test
    fun cancelingPlayOverlayKeepsModalWithoutErrorAndOkClearsIt() = runTest {
        val gateway = FakeGateway().apply {
            checkResult = PlayUpdateCheckResult(
                availability = PlayUpdateAvailability.AVAILABLE,
                immediateAllowed = true,
                availableVersionCode = 25,
            )
        }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val coordinator = AppUpdateCoordinator(scope, gateway)

        coordinator.onResume(fakeLauncher)
        scope.advanceUntilIdle()
        coordinator.onConfirmUpdate(fakeLauncher)

        coordinator.onUpdateFlowResult(Activity.RESULT_CANCELED)
        assertTrue(coordinator.state.value.modalVisible)
        assertFalse(coordinator.state.value.flowInProgress)
        assertNull(coordinator.state.value.errorRes)

        coordinator.onUpdateFlowResult(PlayUpdateActivityResult.RESULT_IN_APP_UPDATE_FAILED)
        assertTrue(coordinator.state.value.modalVisible)
        assertEquals(R.string.app_update_launch_error, coordinator.state.value.errorRes)

        coordinator.onUpdateFlowResult(Activity.RESULT_OK)
        assertFalse(coordinator.state.value.modalVisible)
        assertNull(coordinator.state.value.errorRes)
    }

    @Test
    fun offlineCheckDoesNotDismissAlreadyVisibleUpdateModal() = runTest {
        val gateway = FakeGateway().apply {
            checkResult = PlayUpdateCheckResult(
                availability = PlayUpdateAvailability.AVAILABLE,
                immediateAllowed = true,
                availableVersionCode = 25,
            )
        }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val coordinator = AppUpdateCoordinator(scope, gateway)

        coordinator.onResume(fakeLauncher)
        scope.advanceUntilIdle()
        assertTrue(coordinator.state.value.modalVisible)

        gateway.checkResult = null
        coordinator.onResume(fakeLauncher)
        scope.advanceUntilIdle()
        assertTrue(coordinator.state.value.modalVisible)
    }

    @Test
    fun launchFailureAndMissingStoreExposeRecoveryErrors() = runTest {
        val gateway = FakeGateway().apply {
            checkResult = PlayUpdateCheckResult(
                availability = PlayUpdateAvailability.AVAILABLE,
                immediateAllowed = true,
                availableVersionCode = 25,
            )
            launchResult = PlayUpdateLaunchResult.Failed
        }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val coordinator = AppUpdateCoordinator(scope, gateway)

        coordinator.onResume(fakeLauncher)
        scope.advanceUntilIdle()
        coordinator.onConfirmUpdate(fakeLauncher)

        assertTrue(coordinator.state.value.modalVisible)
        assertEquals(R.string.app_update_launch_error, coordinator.state.value.errorRes)

        coordinator.onStoreUnavailable()
        assertEquals(R.string.app_update_play_unavailable, coordinator.state.value.errorRes)
    }
}
