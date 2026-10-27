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
            coordinator.authorizeLocationUpload { pendingOff }
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
        var pendingOff = false

        assertTrue(coordinator.authorizeLocationUpload { pendingOff })
        coordinator.withLocalMutation { pendingOff = true }

        assertFalse(coordinator.authorizeLocationUpload { pendingOff })
    }
}
