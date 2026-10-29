package com.anve.app.ui.map

import com.anve.app.data.api.VisibleLocationDto
import com.anve.app.data.location.AudienceMember
import com.anve.app.data.location.AudienceMutationOutcome
import com.anve.app.data.location.SharingManagementDataSource
import com.anve.app.data.location.SharingManagementSnapshot
import com.anve.app.data.location.VisibleLocationSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveMapViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: FakeSharingDataSource
    private lateinit var viewModel: LiveMapViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeSharingDataSource()
        viewModel = LiveMapViewModel(repository)
    }

    @After
    fun tearDown() {
        viewModel.ownerChanged(null)
        Dispatchers.resetMain()
    }

    @Test
    fun `pending stays until mutation and authoritative snapshot reconcile`() {
        val snapshotGate = CompletableDeferred<SharingManagementSnapshot>()
        repository.management = { call, _ ->
            if (call == 2) snapshotGate.await() else repository.current
        }
        repository.current = sharing(grants = emptySet())
        viewModel.ownerChanged("owner-a")

        repository.mutation = { _, _, enabled ->
            assertTrue(enabled)
            AudienceMutationOutcome.Confirmed(snapshot = null)
        }
        viewModel.toggleViewer("owner-a", member("viewer-a"), true)

        assertTrue("switch remains pending while authoritative refresh is in flight", "viewer-a" in viewModel.state.value.pendingViewerIds)
        repository.current = sharing(grants = setOf("viewer-a"))
        snapshotGate.complete(repository.current)

        assertTrue(viewModel.state.value.sharing!!.members.single { it.userId == "viewer-a" }.canViewLocation)
        assertFalse("viewer-a" in viewModel.state.value.pendingViewerIds)
    }

    @Test
    fun `unknown revoke does not claim viewer lost access when snapshot still grants it`() {
        repository.current = sharing(grants = setOf("viewer-a"))
        viewModel.ownerChanged("owner-a")
        repository.mutation = { _, _, enabled ->
            assertFalse(enabled)
            AudienceMutationOutcome.UnknownOutcome(
                snapshot = sharing(grants = setOf("viewer-a")),
                requestedEnabled = false,
                currentEnabled = true,
            )
        }

        viewModel.toggleViewer("owner-a", member("viewer-a", granted = true), false)

        assertTrue(viewModel.state.value.sharing!!.members.single { it.userId == "viewer-a" }.canViewLocation)
        assertFalse("viewer-a" in viewModel.state.value.unresolvedViewerIds)
        assertFalse("viewer-a" in viewModel.state.value.pendingViewerIds)
        assertTrue(viewModel.state.value.sharingError.orEmpty().contains("vẫn cho biết"))
        assertEquals(1, repository.mutationCalls)
        repository.mutation = { _, _, _ ->
            repository.current = sharing(grants = emptySet())
            AudienceMutationOutcome.Confirmed(null)
        }
        viewModel.toggleViewer("owner-a", member("viewer-a", granted = true), false)
        assertEquals(2, repository.mutationCalls)
        assertFalse(viewModel.state.value.sharing!!.members.single { it.userId == "viewer-a" }.canViewLocation)
    }

    @Test
    fun `unknown mutation and failed reconciliation stay unresolved until a later read`() {
        repository.current = sharing(grants = emptySet())
        viewModel.ownerChanged("owner-a")
        repository.mutation = { _, _, _ ->
            AudienceMutationOutcome.UnknownOutcome(null, requestedEnabled = true, currentEnabled = null)
        }
        repository.management = { call, _ ->
            if (call == 2) throw IllegalStateException("read unavailable")
            sharing(grants = emptySet())
        }

        viewModel.toggleViewer("owner-a", member("viewer-a"), true)

        assertTrue("viewer-a" in viewModel.state.value.pendingViewerIds)
        assertTrue("viewer-a" in viewModel.state.value.unresolvedViewerIds)
        assertTrue(viewModel.state.value.sharingError.orEmpty().contains("chờ đối chiếu"))
        assertEquals(1, repository.mutationCalls)
        viewModel.toggleViewer("owner-a", member("viewer-a"), true)
        assertEquals(1, repository.mutationCalls)

        repository.management = { _, _ -> sharing(grants = emptySet()) }
        viewModel.refreshSharing("owner-a")

        assertFalse("viewer-a" in viewModel.state.value.pendingViewerIds)
        assertFalse("viewer-a" in viewModel.state.value.unresolvedViewerIds)
        assertTrue(viewModel.state.value.sharingError.orEmpty().contains("chưa hiển thị"))
        assertEquals(1, repository.mutationCalls)
        repository.mutation = { _, _, _ ->
            repository.current = sharing(grants = setOf("viewer-a"))
            AudienceMutationOutcome.Confirmed(null)
        }
        repository.management = { _, _ -> repository.current }
        viewModel.toggleViewer("owner-a", member("viewer-a"), true)
        assertEquals(2, repository.mutationCalls)
        assertTrue(viewModel.state.value.sharing!!.members.single { it.userId == "viewer-a" }.canViewLocation)
    }

    @Test
    fun `unknown revoke reconciles when server already revoked without replay`() {
        repository.current = sharing(grants = setOf("viewer-a"))
        viewModel.ownerChanged("owner-a")
        repository.mutation = { _, _, _ ->
            repository.current = sharing(grants = emptySet())
            AudienceMutationOutcome.UnknownOutcome(null, requestedEnabled = false, currentEnabled = null)
        }
        viewModel.toggleViewer("owner-a", member("viewer-a", granted = true), false)
        assertEquals(1, repository.mutationCalls)
        assertFalse(viewModel.state.value.sharing!!.members.single { it.userId == "viewer-a" }.canViewLocation)
        assertTrue(viewModel.state.value.unresolvedViewerIds.isEmpty())
        assertTrue(viewModel.state.value.pendingViewerIds.isEmpty())
    }

    @Test
    fun `double tap does not start a second mutation for the same viewer`() {
        repository.current = sharing(grants = emptySet())
        viewModel.ownerChanged("owner-a")
        val mutationGate = CompletableDeferred<AudienceMutationOutcome>()
        repository.mutation = { _, _, _ -> mutationGate.await() }

        viewModel.toggleViewer("owner-a", member("viewer-a"), true)
        viewModel.toggleViewer("owner-a", member("viewer-a"), false)

        assertEquals(1, repository.mutationCalls)
        assertTrue("viewer-a" in viewModel.state.value.pendingViewerIds)

        repository.current = sharing(grants = setOf("viewer-a"))
        mutationGate.complete(AudienceMutationOutcome.Confirmed(null))
        assertTrue(viewModel.state.value.sharing!!.members.single { it.userId == "viewer-a" }.canViewLocation)
        assertFalse("viewer-a" in viewModel.state.value.pendingViewerIds)
    }

    @Test
    fun `concurrent viewers reconcile to a snapshot that contains both mutations`() {
        val both = sharing(grants = setOf("viewer-a", "viewer-b"), includeSecondViewer = true)
        repository.current = sharing(grants = emptySet(), includeSecondViewer = true)
        viewModel.ownerChanged("owner-a")
        val firstMutation = CompletableDeferred<AudienceMutationOutcome>()
        val secondMutation = CompletableDeferred<AudienceMutationOutcome>()
        repository.mutation = { _, viewerId, _ ->
            if (viewerId == "viewer-a") firstMutation.await() else secondMutation.await()
        }
        val staleRead = CompletableDeferred<SharingManagementSnapshot>()
        repository.management = { call, _ ->
            when (call) {
                2 -> withContext(NonCancellable) { staleRead.await() }
                else -> both
            }
        }

        viewModel.toggleViewer("owner-a", member("viewer-a"), true)
        viewModel.toggleViewer("owner-a", member("viewer-b"), true)
        assertEquals(2, repository.mutationCalls)

        secondMutation.complete(AudienceMutationOutcome.Confirmed(null))
        firstMutation.complete(AudienceMutationOutcome.Confirmed(null))
        staleRead.complete(sharing(grants = setOf("viewer-b"), includeSecondViewer = true))

        val grants = viewModel.state.value.sharing!!.members.filter { it.canViewLocation }.map { it.userId }.toSet()
        assertEquals(setOf("viewer-a", "viewer-b"), grants)
        assertTrue(viewModel.state.value.pendingViewerIds.isEmpty())
    }

    @Test
    fun `stale sharing refresh cannot overwrite newer refresh`() {
        repository.current = sharing(grants = emptySet())
        viewModel.ownerChanged("owner-a")
        val stale = CompletableDeferred<SharingManagementSnapshot>()
        val fresh = CompletableDeferred<SharingManagementSnapshot>()
        repository.management = { call, _ ->
            when (call) {
                2 -> withContext(NonCancellable) { stale.await() }
                else -> fresh.await()
            }
        }

        viewModel.refreshSharing("owner-a")
        viewModel.refreshSharing("owner-a")
        fresh.complete(sharing(grants = setOf("viewer-a")))
        stale.complete(sharing(grants = emptySet()))

        assertTrue(viewModel.state.value.sharing!!.members.single { it.userId == "viewer-a" }.canViewLocation)
    }

    @Test
    fun `account switch ignores old mutation and old sharing response`() {
        repository.current = sharing(grants = emptySet())
        viewModel.ownerChanged("owner-a")
        val mutationGate = CompletableDeferred<AudienceMutationOutcome>()
        repository.mutation = { _, _, _ -> withContext(NonCancellable) { mutationGate.await() } }
        viewModel.toggleViewer("owner-a", member("viewer-a"), true)

        repository.current = sharing(ownerId = "owner-b", grants = emptySet())
        repository.management = { _, expectedOwner -> sharing(ownerId = expectedOwner, grants = emptySet()) }
        viewModel.ownerChanged("owner-b")
        viewModel.refreshSharing("owner-b")
        mutationGate.complete(AudienceMutationOutcome.Confirmed(sharing(grants = setOf("viewer-a"))))

        assertEquals("owner-b", viewModel.state.value.ownerId)
        assertTrue(viewModel.state.value.pendingViewerIds.isEmpty())
        assertFalse(viewModel.state.value.sharing!!.members.any { it.canViewLocation })
    }

    @Test
    fun `account switch ignores reconciliation response from old owner`() {
        repository.current = sharing(grants = setOf("viewer-a"))
        viewModel.ownerChanged("owner-a")
        val oldRead = CompletableDeferred<SharingManagementSnapshot>()
        repository.management = { _, owner ->
            if (owner == "owner-a") withContext(NonCancellable) { oldRead.await() }
            else sharing(ownerId = owner, grants = emptySet())
        }
        repository.mutation = { _, _, _ -> AudienceMutationOutcome.UnknownOutcome(null, false, null) }
        viewModel.toggleViewer("owner-a", member("viewer-a", granted = true), false)
        viewModel.ownerChanged("owner-b")
        oldRead.complete(sharing(grants = setOf("viewer-a")))
        assertEquals("owner-b", viewModel.state.value.ownerId)
        assertTrue(viewModel.state.value.unresolvedViewerIds.isEmpty())
        assertTrue(viewModel.state.value.pendingViewerIds.isEmpty())
        assertFalse(viewModel.state.value.sharing!!.members.any { it.canViewLocation })
    }

    @Test
    fun `old location snapshot cannot restore a marker after a newer collection`() {
        repository.current = sharing(grants = emptySet())
        viewModel.ownerChanged("owner-a")
        val oldCollection = viewModel.beginLocationSnapshotCollection("owner-a")
        val oldLocation = VisibleLocationDto(
            ownerId = "member-a",
            displayName = "Mai",
            latitude = 10.0,
            longitude = 106.0,
            horizontalAccuracyMeters = 10.0,
            recordedAt = "2026-10-28T00:00:00Z",
            receivedAt = "2026-10-28T00:00:01Z",
            ageMillis = 1_000,
            confidence = "FRESH",
        )
        viewModel.applySnapshot(VisibleLocationSnapshot("owner-a", listOf(oldLocation)), oldCollection)

        val currentCollection = viewModel.beginLocationSnapshotCollection("owner-a")
        viewModel.applySnapshot(VisibleLocationSnapshot("owner-a", emptyList()), currentCollection)
        viewModel.applySnapshot(VisibleLocationSnapshot("owner-a", listOf(oldLocation)), oldCollection)

        assertTrue(viewModel.state.value.snapshot!!.locations.isEmpty())
    }

    @Test
    fun `explicit rejection is not shown as uncertain or confirmed`() {
        repository.current = sharing(grants = emptySet())
        viewModel.ownerChanged("owner-a")
        repository.mutation = { _, _, _ ->
            AudienceMutationOutcome.Rejected(null, "not allowed")
        }

        viewModel.toggleViewer("owner-a", member("viewer-a"), true)

        assertFalse(viewModel.state.value.sharing!!.members.single { it.userId == "viewer-a" }.canViewLocation)
        assertTrue(viewModel.state.value.sharingError.orEmpty().contains("từ chối"))
        assertFalse("viewer-a" in viewModel.state.value.unresolvedViewerIds)
    }

    private fun member(id: String, granted: Boolean = false) = AudienceMember(
        userId = id,
        displayName = id,
        role = "member",
        canViewLocation = granted,
    )

    private fun sharing(
        ownerId: String = "owner-a",
        grants: Set<String>,
        includeSecondViewer: Boolean = false,
    ) = SharingManagementSnapshot(
        hasCircle = true,
        circleName = "Family",
        consentEnabled = true,
        members = buildList {
            add(AudienceMember(ownerId, "Owner", "owner", false))
            add(AudienceMember("viewer-a", "Viewer A", "member", "viewer-a" in grants))
            if (includeSecondViewer) add(AudienceMember("viewer-b", "Viewer B", "member", "viewer-b" in grants))
        },
    )

    private inner class FakeSharingDataSource : SharingManagementDataSource {
        var current = sharing(grants = emptySet())
        var mutationCalls = 0
        var managementCalls = 0
        var management: suspend (Int, String) -> SharingManagementSnapshot = { _, _ -> current }
        var mutation: suspend (String, String, Boolean) -> AudienceMutationOutcome = { _, _, enabled ->
            AudienceMutationOutcome.Confirmed(sharing(grants = if (enabled) setOf("viewer-a") else emptySet()))
        }

        override suspend fun sharingManagement(expectedOwnerId: String): SharingManagementSnapshot =
            management(++managementCalls, expectedOwnerId)

        override suspend fun setViewerGrant(
            ownerId: String,
            viewerId: String,
            enabled: Boolean,
        ): AudienceMutationOutcome {
            mutationCalls++
            return mutation(ownerId, viewerId, enabled)
        }
    }
}
