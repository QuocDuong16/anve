package com.anve.app.data.journey

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyOutboxInstrumentedTest {
    @Test fun encryptedOperationsSurviveReopenAndRemainOwnerBound() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val original = JourneyOperation(owner = owner, journeyId = UUID.randomUUID().toString(), action = JourneyAction.CREATE, createdAtMillis = System.currentTimeMillis())
        val first = EncryptedJourneyOutbox(context)
        try {
            first.put(original)
            val reopened = EncryptedJourneyOutbox(context)
            assertEquals(original, reopened.read(owner).single())
            assertTrue(reopened.read(other).isEmpty())
            reopened.remove(other, original.operationId)
            assertEquals(original, reopened.read(owner).single())
            val cancel = original.copy(operationId = UUID.randomUUID().toString(), action = JourneyAction.CANCEL)
            reopened.put(cancel)
            first.remove(owner, original.operationId)
            assertEquals(cancel, reopened.read(owner).single())
            reopened.remove(owner, cancel.operationId)
            assertTrue(first.read(owner).isEmpty())
        } finally {
            first.read(owner).forEach { first.remove(owner, it.operationId) }
        }
    }
}
