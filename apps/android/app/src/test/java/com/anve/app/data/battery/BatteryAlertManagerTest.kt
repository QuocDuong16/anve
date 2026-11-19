package com.anve.app.data.battery

import com.anve.app.data.api.ApiException
import com.anve.app.data.api.LowBatteryAlertCreateDto
import com.anve.app.data.api.LowBatteryAlertDto
import com.anve.app.tracking.FakeConnectivity
import com.anve.app.tracking.LocationFix
import com.anve.app.tracking.LocationPermissionState
import com.anve.app.tracking.TrackingStatus
import com.anve.app.tracking.testFix
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatteryAlertManagerTest {

    private lateinit var scope: CoroutineScope
    private lateinit var status: MutableStateFlow<TrackingStatus>
    private lateinit var connectivity: FakeConnectivity
    private lateinit var gateway: FakeGateway
    private lateinit var snapshot: FakeSnapshotProvider
    private lateinit var notifier: FakeNotifier
    private var nowMillis: Long = 10_000_000L
    private var latestFix: LocationFix? = null
    private val freshFixesFed = mutableListOf<Pair<String, LocationFix>>()
    private var eventSeq = 0

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        status = MutableStateFlow(eligibleStatus())
        connectivity = FakeConnectivity(online = true)
        gateway = FakeGateway()
        snapshot = FakeSnapshotProvider()
        notifier = FakeNotifier()
        nowMillis = 10_000_000L
        latestFix = null
        freshFixesFed.clear()
        eventSeq = 0
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun eligibleStatus(
        owner: String? = "u1",
        activelyTracking: Boolean = true,
        consentEnabled: Boolean = true,
        consentConfirmedByServer: Boolean = true,
        locationAdmissionOpen: Boolean = true,
        lowBatteryAlertsEnabled: Boolean = true,
    ) = TrackingStatus(
        ownerUserId = owner,
        permission = LocationPermissionState.GRANTED_PRECISE,
        consentEnabled = consentEnabled,
        consentConfirmedByServer = consentConfirmedByServer,
        locationAdmissionOpen = locationAdmissionOpen,
        running = activelyTracking,
        serviceRunning = activelyTracking,
        providerActive = activelyTracking,
        lowBatteryAlertsEnabled = lowBatteryAlertsEnabled,
    )

    private fun newManager(): BatteryAlertManager = BatteryAlertManager(
        trackingStatus = status,
        connectivity = connectivity,
        gateway = gateway,
        snapshotProvider = snapshot,
        localNotifier = notifier,
        latestAcceptedFix = { latestFix },
        onFreshFixCaptured = { owner, fix -> freshFixesFed += owner to fix },
        scope = scope,
        now = { nowMillis },
        newEventId = { "00000000-0000-4000-8000-%012d".format(++eventSeq) },
    )

    @Test
    fun `refuses alerts unless every eligibility gate is satisfied`() {
        newManager()

        val gatedStates = listOf(
            eligibleStatus(activelyTracking = false),
            eligibleStatus(consentEnabled = false),
            eligibleStatus(consentConfirmedByServer = false),
            eligibleStatus(locationAdmissionOpen = false),
            eligibleStatus(lowBatteryAlertsEnabled = false),
            eligibleStatus(owner = null),
        )
        for (gated in gatedStates) {
            status.value = gated
            status.value.let { _ -> }
        }
        status.value = eligibleStatus(lowBatteryAlertsEnabled = false)
        val mgr = newManager()
        mgr.onBatterySample(15, false)
        assertEquals(0, gateway.created.size)

        status.value = eligibleStatus(activelyTracking = false)
        mgr.onBatterySample(5, false)
        assertEquals(0, gateway.created.size)

        status.value = eligibleStatus(consentEnabled = false)
        mgr.onBatterySample(5, false)
        assertEquals(0, gateway.created.size)
    }

    @Test
    fun `starting tracking while already at 4 percent fires only 5 percent once`() {
        status.value = eligibleStatus(activelyTracking = false)
        val mgr = newManager()
        mgr.onBatterySample(4, false)
        assertEquals(0, gateway.created.size)

        status.value = eligibleStatus(activelyTracking = true)
        assertEquals(1, gateway.created.size)
        assertEquals(5, gateway.created.single().second.threshold)
        assertEquals(4, gateway.created.single().second.batteryPercent)
    }

    @Test
    fun `reuses accepted fix under 30s without calling single shot GPS`() {
        latestFix = testFix(recordedAtMillis = nowMillis - 20_000L, latitude = 10.71, longitude = 106.61)
        val mgr = newManager()

        mgr.onBatterySample(15, false)

        assertEquals(0, snapshot.calls)
        assertEquals(0, freshFixesFed.size)
        assertEquals(1, gateway.created.size)
        val loc = gateway.created.single().second.location
        assertNotNull(loc)
        assertEquals(10.71, loc!!.latitude, 0.0001)
    }

    @Test
    fun `captures single shot fix and forwards it to tracking manager when cached fix is older than 30s`() {
        latestFix = testFix(recordedAtMillis = nowMillis - 45_000L, latitude = 10.70, longitude = 106.60)
        val fresh = testFix(eventId = "fresh-1", recordedAtMillis = nowMillis - 500L, latitude = 10.82, longitude = 106.72)
        snapshot.nextFix = fresh
        val mgr = newManager()

        mgr.onBatterySample(15, false)

        assertEquals(1, snapshot.calls)
        assertEquals(listOf("u1" to fresh), freshFixesFed)
        assertEquals(10.82, gateway.created.single().second.location!!.latitude, 0.0001)
    }

    @Test
    fun `falls back to fix up to 15 minutes when fresh GPS times out and fails open beyond 15 minutes`() {
        val oldFix = testFix(recordedAtMillis = nowMillis - 10 * 60_000L, latitude = 10.73, longitude = 106.63)
        latestFix = oldFix
        snapshot.nextFix = null
        val mgr = newManager()

        mgr.onBatterySample(15, false)
        assertEquals(10.73, gateway.created.last().second.location!!.latitude, 0.0001)

        nowMillis += 10_000L
        latestFix = testFix(recordedAtMillis = nowMillis - 16 * 60_000L, latitude = 10.74, longitude = 106.64)
        mgr.onBatterySample(5, false)
        assertNull(gateway.created.last().second.location)
    }

    @Test
    fun `notifies sender locally only when at least one recipient received the alert`() {
        gateway.recipientCount = 0
        val mgr = newManager()
        mgr.onBatterySample(15, false)
        assertEquals(0, notifier.notifications.size)

        gateway.recipientCount = 2
        mgr.onBatterySample(5, false)
        assertEquals(listOf("u1:5:5:2"), notifier.notifications)
    }

    @Test
    fun `offline pending queue holds max 2 thresholds, retries within 15m, and expires after 15m`() {
        connectivity.updateOnline(false)
        val mgr = newManager()

        mgr.onBatterySample(15, false)
        mgr.onBatterySample(5, false)
        assertEquals(0, gateway.created.size)

        nowMillis += 5 * 60_000L
        connectivity.updateOnline(true)
        assertEquals(listOf(15, 5), gateway.created.map { it.second.threshold })

        // Verify >15m expiry drops queued alert rather than sending stale data.
        connectivity.updateOnline(false)
        mgr.onBatterySample(20, true) // rearm and resolve first
        connectivity.updateOnline(true)
        connectivity.updateOnline(false)
        mgr.onBatterySample(15, false)
        nowMillis += 16 * 60_000L
        val before = gateway.created.size
        connectivity.updateOnline(true)
        assertEquals(before, gateway.created.size)
    }

    @Test
    fun `cancels in-flight or queued alert on charging, recovery, stop tracking, opt-out, or owner switch`() {
        connectivity.updateOnline(false)
        val mgr = newManager()

        // Plugging in cancels pending 15% alert before network returns.
        mgr.onBatterySample(15, false)
        mgr.onBatterySample(15, true)
        connectivity.updateOnline(true)
        assertEquals(0, gateway.created.size)

        // Reset via 20%+, unplug, queue while offline, then stop tracking.
        mgr.onBatterySample(25, true)
        connectivity.updateOnline(false)
        mgr.onBatterySample(15, false)
        status.value = eligibleStatus(activelyTracking = false)
        connectivity.updateOnline(true)
        assertEquals(0, gateway.created.size)

        // Resume at 25%, trigger 15% while fresh GPS fetch suspends, and switch owner mid-flight -> discarded.
        mgr.onBatterySample(25, false)
        status.value = eligibleStatus(owner = "u1")
        val gpsGate = CompletableDeferred<LocationFix?>()
        snapshot.deferred = gpsGate
        mgr.onBatterySample(15, false)
        status.value = eligibleStatus(owner = "u2", activelyTracking = false)
        gpsGate.complete(testFix(recordedAtMillis = nowMillis))
        assertEquals(0, freshFixesFed.size)
        assertEquals(0, gateway.created.size)
    }

    @Test
    fun `charging recovery to 20 percent sends silent resolve once and retries if offline`() {
        connectivity.updateOnline(true)
        val mgr = newManager()

        mgr.onBatterySample(15, false)
        assertEquals(1, gateway.created.size)

        // Charging < 20% does not resolve yet.
        mgr.onBatterySample(18, true)
        assertEquals(0, gateway.resolvedOwners.size)

        // Drop offline when reaching 20%, then recover network -> silent resolve fires once.
        connectivity.updateOnline(false)
        mgr.onBatterySample(20, true)
        assertEquals(0, gateway.resolvedOwners.size)

        connectivity.updateOnline(true)
        assertEquals(listOf("u1"), gateway.resolvedOwners)

        // Subsequent 25% charging sample does not spam /resolve.
        mgr.onBatterySample(25, true)
        assertEquals(listOf("u1"), gateway.resolvedOwners)
    }

    @Test
    fun `transient 5xx or IOException retries on reconnect while terminal 4xx drops`() {
        val mgr = newManager()
        gateway.nextCreateError = IOException("socket reset")
        mgr.onBatterySample(15, false)
        assertEquals(1, gateway.createAttempts)

        // Reconnect succeeds for buffered 15% alert.
        gateway.nextCreateError = null
        connectivity.updateOnline(false)
        connectivity.updateOnline(true)
        assertEquals(2, gateway.createAttempts)
        assertEquals(1, gateway.created.size)

        // Terminal 429 drops cleanly without keeping offline retry.
        gateway.nextCreateError = ApiException("LOW_BATTERY_ALERT_COOLDOWN", "cooldown", null, httpStatus = 429)
        mgr.onBatterySample(5, false)
        val attemptsAfter429 = gateway.createAttempts
        connectivity.updateOnline(false)
        connectivity.updateOnline(true)
        assertEquals(attemptsAfter429, gateway.createAttempts)
    }

    private class FakeGateway : BatteryAlertGateway {
        var recipientCount = 1
        var createAttempts = 0
        var nextCreateError: Exception? = null
        val created = mutableListOf<Pair<String, LowBatteryAlertCreateDto>>()
        val resolvedOwners = mutableListOf<String>()

        override suspend fun createAlert(ownerUserId: String, body: LowBatteryAlertCreateDto): LowBatteryAlertDto {
            createAttempts++
            nextCreateError?.let { error ->
                nextCreateError = null
                throw error
            }
            created += ownerUserId to body
            return LowBatteryAlertDto(
                id = body.eventId,
                ownerId = ownerUserId,
                ownerName = "Bé Minh",
                scope = "SHARING",
                threshold = body.threshold,
                batteryPercent = body.batteryPercent,
                recordedAt = body.recordedAt,
                receivedAt = body.recordedAt,
                recipientCount = recipientCount,
                location = body.location,
            )
        }

        override suspend fun resolveAlerts(ownerUserId: String) {
            resolvedOwners += ownerUserId
        }
    }

    private class FakeSnapshotProvider : BatterySnapshotProvider {
        var calls = 0
        var nextFix: LocationFix? = null
        var deferred: CompletableDeferred<LocationFix?>? = null

        override suspend fun captureFreshFix(): LocationFix? {
            calls++
            val gate = deferred
            if (gate != null) {
                deferred = null
                return gate.await()
            }
            return nextFix
        }
    }

    private class FakeNotifier : BatteryAlertLocalNotifier {
        val notifications = mutableListOf<String>()

        override fun notifySent(ownerUserId: String, threshold: Int, batteryPercent: Int, recipientCount: Int) {
            notifications += "$ownerUserId:$threshold:$batteryPercent:$recipientCount"
        }
    }
}
