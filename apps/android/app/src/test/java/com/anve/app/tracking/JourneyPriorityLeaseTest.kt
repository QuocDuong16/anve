package com.anve.app.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyPriorityLeaseTest {
    @Test fun `reads cannot start priority and an old reply cannot restore a stopped lease`() {
        var elapsed = 1000L
        val lease = JourneyPriorityLease { elapsed }
        val ticket = lease.revision
        assertFalse(lease.renew("a", "trip", ticket))
        assertTrue(lease.activate("a", "trip", 120_000, ticket))
        assertTrue(lease.active("a"))
        assertFalse(lease.active("b"))
        lease.clear()
        assertFalse(lease.renew("a", "trip", ticket))
        assertFalse(lease.activate("a", "trip", 120_000, ticket))
        assertFalse(lease.active("a"))
        elapsed += 10_000
        assertTrue(lease.activate("b", "new-trip", 120_000, lease.revision))
        assertFalse(lease.renew("a", "trip", ticket))
        assertTrue(lease.active("b"))
    }

    @Test fun `failed confirmation expires without extending the hard journey expiry`() {
        var elapsed = 0L
        val lease = JourneyPriorityLease { elapsed }
        assertTrue(lease.activate("a", "trip", 90_000, lease.revision))
        elapsed = 50_000
        assertTrue(lease.renew("a", "trip", lease.revision))
        elapsed = 90_000
        assertFalse(lease.active("a"))
        assertFalse(lease.renew("a", "trip", lease.revision))
        assertTrue(lease.activate("a", "trip2", 600_000, lease.revision))
        elapsed += 60_000
        assertFalse(lease.active("a"))
        assertFalse(lease.renew("a", "trip2", lease.revision))
    }

    @Test fun `a new process starts without any acquisition admission`() {
        val first = JourneyPriorityLease { 0L }
        assertTrue(first.activate("a", "trip", 100_000, first.revision))
        val restarted = JourneyPriorityLease { 0L }
        assertFalse(restarted.active("a"))
        assertFalse(restarted.renew("a", "trip", restarted.revision))
    }
}
