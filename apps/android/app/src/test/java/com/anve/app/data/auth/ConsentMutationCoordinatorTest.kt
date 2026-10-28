package com.anve.app.data.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentMutationCoordinatorTest {

    @Test
    fun `persisted consent OFF prevents a later upload start permit`() = runBlocking {
        val coordinator = ConsentMutationCoordinator()
        assertTrue(coordinator.reconcileLocationAdmission("user-a", enabled = true))
        var pendingOff = false
        val mutationEntered = CompletableDeferred<Unit>()
        val finishMutation = CompletableDeferred<Unit>()

        val mutation = async {
            coordinator.withLocalMutation {
                pendingOff = true
                mutationEntered.complete(Unit)
                finishMutation.await()
            }
        }
        mutationEntered.await()
        val uploadAttempted = CompletableDeferred<Unit>()
        val upload = async {
            uploadAttempted.complete(Unit)
            coordinator.authorizeLocationUpload("user-a") { pendingOff }
        }
        uploadAttempted.await()
        yield()
        finishMutation.complete(Unit)

        mutation.await()
        assertFalse(upload.await())
    }

    @Test
    fun `upload permit linearized before consent OFF is allowed as in flight work`() = runBlocking {
        val coordinator = ConsentMutationCoordinator()
        assertTrue(coordinator.reconcileLocationAdmission("user-a", enabled = true))
        var pendingOff = false

        assertTrue(coordinator.authorizeLocationUpload("user-a") { pendingOff })
        coordinator.withLocalMutation { pendingOff = true }

        assertFalse(coordinator.authorizeLocationUpload("user-a") { pendingOff })
    }

    @Test
    fun `owner admission does not leak across accounts or reopen after local OFF`() = runBlocking {
        val coordinator = ConsentMutationCoordinator()

        assertTrue(coordinator.isLocationAdmissionClosed("user-a"))
        assertTrue(coordinator.isLocationAdmissionClosed("user-b"))
        assertTrue(coordinator.reconcileLocationAdmission("user-a", enabled = true))
        assertFalse(coordinator.isLocationAdmissionClosed("user-a"))
        assertTrue(coordinator.isLocationAdmissionClosed("user-b"))

        coordinator.closeLocationAdmission("user-a")
        assertTrue(coordinator.isLocationAdmissionClosed("user-a"))
        assertFalse(coordinator.reconcileLocationAdmission("user-a", enabled = true))
        assertTrue(coordinator.isLocationAdmissionClosed("user-a"))

        // B can only open its own gate after an authoritative B snapshot.
        assertTrue(coordinator.reconcileLocationAdmission("user-b", enabled = true))
        assertFalse(coordinator.isLocationAdmissionClosed("user-b"))
        assertTrue(coordinator.isLocationAdmissionClosed("user-a"))
    }

    @Test
    fun `stale ON completion cannot reopen gate after newer OFF barrier`() = runBlocking {
        val coordinator = ConsentMutationCoordinator()
        val staleGeneration = coordinator.nextGeneration("user-a")

        coordinator.closeLocationAdmission("user-a")

        assertFalse(coordinator.openLocationAdmission("user-a", staleGeneration))
        assertTrue(coordinator.isLocationAdmissionClosed("user-a"))
        assertFalse(coordinator.authorizeLocationUpload("user-a") { false })
    }

    @Test
    fun `fresh explicit ON opens only the matching owner generation`() = runBlocking {
        val coordinator = ConsentMutationCoordinator()
        coordinator.closeLocationAdmission("user-a")
        val generation = coordinator.nextGeneration("user-a")

        assertTrue(coordinator.openLocationAdmission("user-a", generation))
        assertFalse(coordinator.isLocationAdmissionClosed("user-a"))
        assertTrue(coordinator.isLocationAdmissionClosed("user-b"))
        assertTrue(coordinator.authorizeLocationUpload("user-a") { false })
        assertFalse(coordinator.authorizeLocationUpload("user-b") { false })
    }
}
