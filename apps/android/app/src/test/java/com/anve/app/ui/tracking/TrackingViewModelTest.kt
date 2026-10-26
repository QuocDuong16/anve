package com.anve.app.ui.tracking

import com.anve.app.tracking.ConsentResult
import com.anve.app.tracking.TrackingControl
import com.anve.app.tracking.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var control: FakeTrackingControl

    private class FakeTrackingControl : TrackingControl {
        val statusFlow = MutableStateFlow(TrackingStatus())
        override val status: StateFlow<TrackingStatus> = statusFlow
        var starts = 0
        var stops = 0
        var retries = 0
        var refreshes = 0
        var refusal: ConsentResult? = null
        var consent: Boolean? = null

        override fun startTracking() {
            starts++
            statusFlow.value = statusFlow.value.copy(trackingRequested = true)
        }

        override fun stopTracking() {
            stops++
            statusFlow.value = statusFlow.value.copy(trackingRequested = false, running = false)
        }

        override fun retrySync() {
            retries++
        }

        override fun refreshStatus() {
            refreshes++
        }

        override suspend fun setConsent(enabled: Boolean): ConsentResult {
            consent = enabled
            return refusal ?: ConsentResult(enabled, serverConfirmed = true)
        }

        override suspend fun recordServerConsent(enabled: Boolean) = Unit
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        control = FakeTrackingControl()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `the screen state follows the coordinator status flow`() = runBlocking {
        val viewModel = TrackingViewModel(control, scope)

        control.statusFlow.value = TrackingStatus(running = true, trackingRequested = true)

        assertEquals(true, viewModel.state.value.status.running)
    }

    @Test
    fun `the controls delegate to the coordinator`() = runBlocking {
        val viewModel = TrackingViewModel(control, scope)

        viewModel.start()
        viewModel.stop()
        viewModel.retrySync()
        viewModel.refresh()

        assertEquals(1, control.starts)
        assertEquals(1, control.stops)
        assertEquals(1, control.retries)
        assertTrue(control.refreshes >= 1)
        assertTrue(viewModel.state.value.message.orEmpty().contains("Đã yêu cầu đồng bộ"))
    }

    @Test
    fun `a consent refusal keeps the switch off and explains why`() = runBlocking {
        control.refusal = ConsentResult(
            enabled = false,
            serverConfirmed = false,
            message = "Không bật được chia sẻ vị trí.",
        )
        val viewModel = TrackingViewModel(control, scope)

        viewModel.setConsent(true)
        control.statusFlow.value = TrackingStatus(consentEnabled = false)

        assertFalse(viewModel.state.value.status.consentEnabled)
        assertEquals("Không bật được chia sẻ vị trí.", viewModel.state.value.message)
        assertEquals(true, control.consent)
    }

    @Test
    fun `dismissing the message clears it without touching the status`() = runBlocking {
        val viewModel = TrackingViewModel(control, scope)
        viewModel.retrySync()

        viewModel.consumeMessage()

        assertTrue(viewModel.state.value.message == null)
    }
}
