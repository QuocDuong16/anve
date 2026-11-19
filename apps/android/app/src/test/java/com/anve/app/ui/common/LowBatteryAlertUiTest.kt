package com.anve.app.ui.common

import com.anve.app.data.api.LowBatteryAlertSummaryDto
import com.anve.app.data.api.PlaceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryAlertUiTest {

    private val places = listOf(
        PlaceDto(
            id = "p-school",
            ownerId = "u1",
            circleId = "c1",
            name = "Trường THCS",
            latitude = 10.7769,
            longitude = 106.7009,
            radiusMeters = 150.0,
            createdAt = "2026-11-01T00:00:00Z",
            updatedAt = "2026-11-01T00:00:00Z",
            sharedWithCircle = true,
        ),
    )

    @Test
    fun `matches inside, nearby, distant, and null-location low battery alerts`() {
        val inside = LowBatteryAlertSummaryDto(
            id = "a1",
            threshold = 5,
            batteryPercent = 5,
            recordedAt = "2026-11-19T07:20:00Z",
            latitude = 10.7769,
            longitude = 106.7009,
        )
        val insideMatch = resolveLowBatteryPlaceMatch(inside, places)
        assertNotNull(insideMatch)
        assertTrue(insideMatch!!.isInside)
        assertEquals("Trường THCS", insideMatch.place.name)

        val nearby = inside.copy(id = "a2", latitude = 10.7810, longitude = 106.7009)
        val nearbyMatch = resolveLowBatteryPlaceMatch(nearby, places)
        assertNotNull(nearbyMatch)
        assertFalse(nearbyMatch!!.isInside)
        assertEquals("Trường THCS", nearbyMatch.place.name)

        val farAway = inside.copy(id = "a3", latitude = 10.9000, longitude = 106.7009)
        assertNull(resolveLowBatteryPlaceMatch(farAway, places))

        val noLocation = inside.copy(id = "a4", latitude = null, longitude = null)
        assertNull(resolveLowBatteryPlaceMatch(noLocation, places))
    }
}
