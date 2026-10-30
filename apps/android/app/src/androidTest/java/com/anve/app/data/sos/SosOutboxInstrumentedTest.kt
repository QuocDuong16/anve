package com.anve.app.data.sos

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SosOutboxInstrumentedTest {
    @Test fun encryptedOutboxSurvivesReconstructionAndIsOwnerScoped() = runBlocking {
        val owner = UUID.randomUUID().toString()
        val event = UUID.randomUUID().toString()
        val recipient = UUID.randomUUID().toString()
        val first = EncryptedSosOutbox(ApplicationProvider.getApplicationContext())
        val item = SosOutboxItem(event, owner, "2026-01-01T00:00:00Z", listOf(recipient), 1, SosLocalState.QUEUED)
        first.enqueue(item)
        try {
            val reopened = EncryptedSosOutbox(ApplicationProvider.getApplicationContext())
            assertEquals(listOf(item), reopened.read(owner))
            assertTrue(reopened.read(UUID.randomUUID().toString()).isEmpty())
        } finally {
            assertTrue(first.replace(owner, event, SosLocalState.QUEUED, SosLocalState.CANCELLED))
            assertTrue(first.removeFinished(owner, event))
        }
    }
}
