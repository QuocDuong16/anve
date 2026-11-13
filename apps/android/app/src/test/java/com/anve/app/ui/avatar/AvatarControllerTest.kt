package com.anve.app.ui.avatar

import com.anve.app.data.api.AvatarStateDto
import com.anve.app.data.auth.InMemorySessionStorage
import com.anve.app.data.auth.StoredSession
import com.anve.app.data.avatar.AvatarGateway
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AvatarControllerTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val sessions = InMemorySessionStorage()
    private suspend fun login(owner: String = "owner") { sessions.save(StoredSession("a", "r", "later", "later", owner)) }

    @Test fun `picker and removal are drafts until save and offline cannot write`() = runTest(dispatcher) {
        login()
        val gateway = Gateway()
        val controller = AvatarController("owner", sessions, gateway, backgroundScope)
        controller.open()
        controller.picked(controller.beginPicker()!!) { byteArrayOf(1, 2) }
        assertEquals(0, gateway.writes)
        controller.chooseRemoval()
        assertFalse(controller.state.value.removeDraft)
        assertFalse(controller.state.value.changed)
        controller.save(true)
        assertEquals(0, gateway.writes)
        controller.picked(controller.beginPicker()!!) { byteArrayOf(1, 2) }
        controller.save(false)
        assertEquals(0, gateway.writes)
        controller.dismiss()
        assertNull(controller.state.value.selection)
        controller.open()
        controller.picked(controller.beginPicker()!!) { byteArrayOf(3) }
        controller.save(true)
        assertEquals(1, gateway.writes)
        assertNotNull(controller.state.value.metadata?.imageId)
        controller.open(); controller.chooseRemoval()
        assertEquals(1, gateway.writes)
        controller.save(true)
        assertEquals(2, gateway.writes)
        assertNull(controller.state.value.metadata?.imageId)
        controller.dispose()
    }

    @Test fun `unknown write reconciles with reads without replay and blocks double save`() = runTest(dispatcher) {
        login()
        val gateway = Gateway()
        val reply = CompletableDeferred<Unit>()
        gateway.onWrite = { reply.await(); throw IOException("timeout") }
        val controller = AvatarController("owner", sessions, gateway, backgroundScope)
        controller.open(); controller.picked(controller.beginPicker()!!) { byteArrayOf(1) }
        controller.save(true); controller.save(true)
        assertEquals(1, gateway.writes)
        reply.complete(Unit)
        assertTrue(controller.state.value.unknown)
        controller.save(true); controller.checkAgain(true)
        assertEquals(1, gateway.writes)
        gateway.value = AvatarStateDto(1, gateway.operation, gateway.operation)
        controller.checkAgain(true)
        assertFalse(controller.state.value.unknown)
        assertNull(controller.state.value.selection)
        assertEquals(1, gateway.writes)
        controller.dispose()
    }

    @Test fun `account switch rejects late picker and late image response`() = runTest(dispatcher) {
        login()
        val gateway = Gateway()
        val controller = AvatarController("owner", sessions, gateway, backgroundScope)
        controller.open()
        val pixels = CompletableDeferred<ByteArray>()
        controller.picked(controller.beginPicker()!!) { withContext(NonCancellable) { pixels.await() } }
        login("other")
        pixels.complete(byteArrayOf(1))
        assertNull(controller.state.value.selection)
        assertNull(controller.state.value.metadata)
        controller.save(true)
        assertEquals(0, gateway.writes)
        controller.dispose()
    }

    private class Gateway : AvatarGateway {
        var value = AvatarStateDto(0)
        var writes = 0
        var operation: String? = null
        var onWrite: (suspend () -> Unit)? = null
        override suspend fun state(owner: String) = value
        override suspend fun replace(owner: String, operationId: String, expectedRevision: Long, bytes: ByteArray): AvatarStateDto {
            writes++; operation = operationId; onWrite?.invoke()
            return AvatarStateDto(expectedRevision + 1, operationId, operationId).also { value = it }
        }
        override suspend fun remove(owner: String, operationId: String, expectedRevision: Long): AvatarStateDto {
            writes++; return AvatarStateDto(expectedRevision + 1, operationId, null).also { value = it }
        }
        override suspend fun image(owner: String, subject: String, imageId: String) = byteArrayOf(1)
    }
}
