package com.anve.app.data.sos

import com.anve.app.data.api.JourneyDestinationDto
import com.anve.app.data.api.JourneyDto
import com.anve.app.data.api.JourneyProgressDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyPushSignalTest {
    private val id = "6f5b1adc-9c46-456e-b321-6dca44f82982"
    private val stale = JourneyDto(id, "owner", "Owner", "ACTIVE", "2026-11-12T00:00:00Z", "2026-11-12T02:00:00Z",
        JourneyDestinationDto("Home", 0.0, 0.0), staleAfterMinutes = 5, staleSince = "2026-11-12T00:05:00Z", staleEpisode = 2)

    @Test fun `late signal for an old gap or ended journey is rejected`() {
        assertTrue(authorizedJourneySignal(stale, "JOURNEY_STALE", id, 2))
        assertFalse(authorizedJourneySignal(stale, "JOURNEY_STALE", id, 1))
        assertFalse(authorizedJourneySignal(stale.copy(state = "ARRIVED"), "JOURNEY_STALE", id, 2))
        assertFalse(authorizedJourneySignal(stale.copy(staleSince = null), "JOURNEY_STALE", id, 2))
        assertFalse(authorizedJourneySignal(stale.copy(staleSince = "invalid"), "JOURNEY_STALE", id, 2))
        assertFalse(authorizedJourneySignal(stale.copy(staleAfterMinutes = 0), "JOURNEY_STALE", id, 2))
        assertFalse(authorizedJourneySignal(stale, "JOURNEY_STALE", "c2e4ed36-b1ef-4af8-a22c-fda2b9756740", 2))
        assertFalse(authorizedJourneySignal(stale.copy(progress = JourneyProgressDto("2026-11-12T00:06:00Z", 0.0, 20.0, false)), "JOURNEY_STALE", id, 2))
    }

    @Test fun `malformed episode and abbreviated UUID are rejected before enqueue`() {
        assertFalse(validPushSignal("JOURNEY_STALE", id, 0))
        assertFalse(validPushSignal("JOURNEY_STALE", id, -1))
        assertFalse(validPushSignal("JOURNEY_ACTIVE", id, 2))
        assertFalse(validPushSignal("JOURNEY_STALE", "1-1-1-1-1", 2))
        assertTrue(validPushSignal("JOURNEY_ACTIVE", id, 0))
        assertTrue(validPushSignal("JOURNEY_STALE", id, 2))
        assertEquals("JOURNEY_ACTIVE:$id", pushSignalIdentity("JOURNEY_ACTIVE", id, 0))
        assertNotEquals(pushSignalIdentity("JOURNEY_STALE", id, 1), pushSignalIdentity("JOURNEY_STALE", id, 2))
    }
}
