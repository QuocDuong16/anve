package com.anve.app.ui.diagnostics

import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.data.auth.StoredSession
import com.anve.app.data.diagnostics.DiagnosticsSource
import com.anve.app.data.location.QueueDiagnostics
import com.anve.app.tracking.ConsentResult
import com.anve.app.tracking.TrackingControl
import com.anve.app.tracking.TrackingStatus
import com.anve.app.tracking.diagnostics.DiagnosticsDetails
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private val sessions = InMemorySessionStorage()
    private val control = Control()

    @After
    fun close() { scope.cancel() }

    @Test
    fun `a late response from the old owner never replaces current diagnostics`() = runBlocking {
        val oldRead = CompletableDeferred<DiagnosticsDetails>()
        sessions.save(session("owner-a"))
        control.status.value = status("owner-a")
        val model = DiagnosticsViewModel(control, sessions, DiagnosticsSource { owner ->
            if (owner == "owner-a") withContext(NonCancellable) { oldRead.await() }
            else DiagnosticsDetails(queue = QueueDiagnostics(2, 1_000, 0, 0))
        }, scope)

        sessions.save(session("owner-b"))
        control.status.value = status("owner-b")
        oldRead.complete(DiagnosticsDetails(queue = QueueDiagnostics(99, 1_000, 9, 1)))

        assertEquals("owner-b", model.state.value.owner)
        assertEquals(2, model.state.value.details?.queue?.queuedPoints)
        assertEquals("owner-b", model.state.value.status.ownerUserId)
    }

    @Test
    fun `sign out clears diagnostics and invalidates an already prepared export`() = runBlocking {
        sessions.save(session("owner-a"))
        control.status.value = status("owner-a")
        val model = DiagnosticsViewModel(control, sessions, DiagnosticsSource { DiagnosticsDetails() }, scope)
        val export = requireNotNull(model.prepareExport("0.2.0", 35))

        sessions.clear()

        assertNull(model.state.value.details)
        assertNull(model.prepareExport("0.2.0", 35))
        assertFalse(model.canShare(export))
    }

    @Test
    fun `a refresh failure is visible and does not turn the queue into zero`() = runBlocking {
        sessions.save(session("owner-a"))
        control.status.value = status("owner-a")
        val model = DiagnosticsViewModel(control, sessions, DiagnosticsSource { throw java.io.IOException("sensitive error sentinel") }, scope)

        assertTrue(model.state.value.readFailed)
        assertFalse(model.state.value.loading)
        assertNull(model.state.value.details)
        val export = requireNotNull(model.prepareExport("0.2.0", 35))
        assertFalse(export.text.contains("sensitive error sentinel"))
        assertTrue(export.text.contains("queuedPoints=null"))
    }

    @Test
    fun `a status from another account is never shown or exported`() = runBlocking {
        sessions.save(session("owner-b"))
        control.status.value = status("owner-a").copy(lastUploadAtMillis = 5_000)
        val model = DiagnosticsViewModel(control, sessions, DiagnosticsSource { DiagnosticsDetails() }, scope)

        assertNull(model.state.value.status.ownerUserId)
        assertNull(model.state.value.status.lastUploadAtMillis)
        assertNull(model.prepareExport("0.2.0", 35))
    }

    @Test
    fun `refresh invalidates an export even when the account stays the same`() = runBlocking {
        sessions.save(session("owner-a"))
        control.status.value = status("owner-a")
        val model = DiagnosticsViewModel(control, sessions, DiagnosticsSource { DiagnosticsDetails() }, scope)
        val export = requireNotNull(model.prepareExport("0.2.0", 35))

        model.refresh()

        assertFalse(model.canShare(export))
    }

    @Test
    fun `local queue is available while remote push checks are still pending`() = runBlocking {
        sessions.save(session("owner-a"))
        control.status.value = status("owner-a")
        val remote = CompletableDeferred<DiagnosticsDetails>()
        val source = object : DiagnosticsSource {
            override suspend fun load(owner: String) = DiagnosticsDetails(queue = QueueDiagnostics(12, 1_000, 0, 0))
            override suspend fun loadRemote(owner: String, local: DiagnosticsDetails) = remote.await()
        }
        val model = DiagnosticsViewModel(control, sessions, source, scope)
        assertEquals(12, model.state.value.details?.queue?.queuedPoints)
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.checkingPush)
        assertTrue(requireNotNull(model.prepareExport("0.2.0", 35)).text.contains("pushRegistered=null"))
        remote.complete(DiagnosticsDetails(pushRegistered = true))
        assertFalse(model.state.value.checkingPush)
    }

    @Test
    fun `hidden diagnostics clear their snapshot and do not read the next account`() = runBlocking {
        sessions.save(session("owner-a"))
        control.status.value = status("owner-a")
        var reads = 0
        val model = DiagnosticsViewModel(control, sessions, DiagnosticsSource { reads++; DiagnosticsDetails() }, scope)
        val export = requireNotNull(model.prepareExport("0.2.0", 35))

        model.deactivate()
        sessions.save(session("owner-b"))
        control.status.value = status("owner-b")

        assertEquals(1, reads)
        assertNull(model.state.value.details)
        assertNull(model.prepareExport("0.2.0", 35))
        assertFalse(model.canShare(export))
        model.activate()
        assertEquals(2, reads)
        assertEquals("owner-b", model.state.value.status.ownerUserId)
    }

    private fun session(owner: String) = StoredSession("access-$owner", "refresh-$owner", "later", "later", userId = owner)
    private fun status(owner: String) = TrackingStatus(ownerUserId = owner, sessionReady = true)

    private class Control : TrackingControl {
        override val status = MutableStateFlow(TrackingStatus())
        override fun startTracking() = Unit
        override fun stopTracking() = Unit
        override fun retrySync() = Unit
        override fun refreshStatus() = Unit
        override suspend fun setConsent(enabled: Boolean) = ConsentResult(enabled, serverConfirmed = true)
        override suspend fun recordServerConsent(enabled: Boolean) = Unit
    }
}
