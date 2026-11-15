package com.anve.app.ui.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceSearchTest {
    @Test
    fun shortQueriesAreNeverSent() {
        assertNull(normalizePlaceQuery(""))
        assertNull(normalizePlaceQuery("  ab "))
    }

    @Test
    fun queryIsTrimmedAndWhitespaceCollapsed() {
        assertEquals("Trường Tiểu học", normalizePlaceQuery("  Trường   Tiểu \n học "))
    }
}
