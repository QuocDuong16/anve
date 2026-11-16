package com.anve.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class AnveTimeTest {

    @Test
    fun `relativeAge formats sub-minute, minutes, hours and days`() {
        assertEquals("vừa xong", AnveTime.relativeAge(0L))
        assertEquals("vừa xong", AnveTime.relativeAge(59_000L))
        assertEquals("15 phút trước", AnveTime.relativeAge(15 * 60_000L))
        assertEquals("2 giờ trước", AnveTime.relativeAge(2 * 60 * 60_000L))
        assertEquals("1 ngày trước", AnveTime.relativeAge(24 * 60 * 60_000L))
    }

    @Test
    fun `compactAge formats concise pin badge labels`() {
        assertEquals("Vừa xong", AnveTime.compactAge(-500L))
        assertEquals("15p trước", AnveTime.compactAge(15 * 60_000L))
        assertEquals("3g trước", AnveTime.compactAge(3 * 60 * 60_000L))
        assertEquals("2ng trước", AnveTime.compactAge(2 * 24 * 60 * 60_000L))
    }

    @Test
    fun `clock returns fallback on invalid input`() {
        assertEquals("--:--", AnveTime.clock(null, "--:--"))
        assertEquals("--:--", AnveTime.clock("not-an-iso-timestamp", "--:--"))
    }
}
