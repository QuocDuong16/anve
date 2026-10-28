package com.anve.app.data.location

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationConfidenceTest {

    @Test
    fun `fresh sample ages to delayed and stale while disconnected`() {
        assertEquals("FRESH", displayLocationConfidence("FRESH", 60_000, 10_000, 70_000))
        assertEquals("DELAYED", displayLocationConfidence("FRESH", 60_000, 10_000, 130_000))
        assertEquals("STALE", displayLocationConfidence("FRESH", 60_000, 10_000, 1_000_000))
    }

    @Test
    fun `client cannot upgrade server confidence`() {
        assertEquals("DELAYED", displayLocationConfidence("DELAYED", 10_000, 0, 20_000))
        assertEquals("STALE", displayLocationConfidence("STALE", 10_000, 0, 20_000))
    }

    @Test
    fun `unknown offline and missing age remain unchanged`() {
        assertEquals("UNKNOWN", displayLocationConfidence("UNKNOWN", null, 0, 60_000))
        assertEquals("OFFLINE", displayLocationConfidence("OFFLINE", null, 0, 60_000))
        assertEquals("FRESH", displayLocationConfidence("FRESH", null, 0, 60_000))
    }

    @Test
    fun `invalid monotonic sample does not make confidence more optimistic`() {
        assertEquals("DELAYED", displayLocationConfidence("DELAYED", 10_000, 20_000, 10_000))
    }
}
