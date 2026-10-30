package com.anve.app.data.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushDestinationTest {
    private val id = "6f5b1adc-9c46-456e-b321-6dca44f82982"

    @Test fun `notification must belong to current account and use an opaque event id`() {
        assertEquals(PushDestination("SOS", id), pushDestination("SOS", id, "a", "a"))
        assertNull(pushDestination("SOS", id, "a", "b"))
        assertNull(pushDestination("SOS", id, "a", null))
        assertNull(pushDestination("SOS", "not-a-uuid", "a", "a"))
        assertNull(pushDestination("OTHER", id, "a", "a"))
    }
}
