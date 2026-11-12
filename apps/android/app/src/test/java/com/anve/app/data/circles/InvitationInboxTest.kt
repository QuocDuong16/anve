package com.anve.app.data.circles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InvitationInboxTest {
    private var now = 100L
    private val inbox = InvitationInbox { now }

    @Test fun `signed-out invitation binds once after login and never crosses accounts`() {
        inbox.offer("anve://invite/ABCD234567")
        val pending = inbox.invitation.value!!
        inbox.accountChanged(null)
        assertNotNull(inbox.invitation.value)
        inbox.accountChanged("a")
        assertEquals("ABCD234567", inbox.current(pending.id, "a")?.code)
        inbox.accountChanged("b")
        assertNull(inbox.invitation.value)
        inbox.accountChanged("a")
        assertNull(inbox.current(pending.id, "a"))
    }

    @Test fun `logout expiry new links and old callbacks cannot restore an invitation`() {
        inbox.accountChanged("a")
        inbox.offer("anve://invite/ABCD234567")
        val old = inbox.invitation.value!!
        inbox.offer("anve://invite/ABCD234568")
        inbox.consume(old.id)
        val newer = inbox.invitation.value!!
        assertEquals("ABCD234568", newer.code)
        assertNull(inbox.current(old.id, "a"))
        now += 15 * 60_000
        assertNull(inbox.current(newer.id, "a"))
        inbox.offer("anve://invite/ABCD234567")
        inbox.accountChanged(null)
        assertNull(inbox.invitation.value)
        inbox.offer("anve://invite/not-a-valid-code")
        assertNull(inbox.invitation.value!!.code)
        assertNull(InvitationInbox().invitation.value)
    }
}
