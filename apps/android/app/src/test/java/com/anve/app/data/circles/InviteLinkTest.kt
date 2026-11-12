package com.anve.app.data.circles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLinkTest {
    private val code = "ABCD234567"

    @Test fun `share and parse only a bounded canonical invitation`() {
        assertEquals("anve://invite/$code", InviteLink.create(code))
        assertEquals(code, InviteLink.parse(InviteLink.create(code)))
        assertNull(InviteLink.create("secret\n"))
        assertNull(InviteLink.parse(null))
    }

    @Test fun `other hosts credentials ports paths encodings and injected metadata are rejected`() {
        listOf(
            "https://evil.example/invite/$code", "anve://evil/$code", "ANVE://invite/$code",
            "anve://invite.evil/$code", "anve://user@invite/$code", "anve://invite:123/$code",
            "anve:invite/$code", "anve://invite/$code/", "anve://invite//$code", "anve://invite/../$code",
            "anve://invite/%41BCD234567", "anve://invite/${code.lowercase()}", "anve://invite/$code?autoJoin=true",
            "anve://invite/$code#consent=true", "anve://invite/TOOSHORT", "anve://invite/$code\n",
            "anve://invite/" + "A".repeat(200),
        ).forEach { assertNull(it, InviteLink.parse(it)) }
    }
}
