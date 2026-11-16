package com.anve.app.ui.map

import com.anve.app.data.api.PlaceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceMatchingTest {

    private fun place(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        radiusMeters: Double,
    ) = PlaceDto(
        id = id,
        ownerId = "user-1",
        circleId = "circle-1",
        name = name,
        latitude = lat,
        longitude = lon,
        radiusMeters = radiusMeters,
        sharedWithCircle = true,
        createdAt = "2026-10-29T00:00:00Z",
        updatedAt = "2026-10-29T00:00:00Z",
    )

    @Test
    fun `prefers inside place and smallest radius when nested`() {
        val district = place("outer", "Quận 1", 10.7769, 106.7009, radiusMeters = 1_000.0)
        val home = place("home", "Nhà", 10.7772, 106.7011, radiusMeters = 120.0)
        val match = findMatchingPlace(10.77725, 106.70112, listOf(district, home))
        assertNotNull(match)
        assertTrue(match!!.isInside)
        assertEquals("Nhà", match.place.name)
    }

    @Test
    fun `falls back to nearby place within two kilometers when outside radius`() {
        val school = place("school", "Trường học", 10.7769, 106.7009, radiusMeters = 100.0)
        // Roughly 550m north of the saved zone.
        val match = findMatchingPlace(10.7819, 106.7009, listOf(school))
        assertNotNull(match)
        assertFalse(match!!.isInside)
        assertEquals("Trường học", match.place.name)
    }

    @Test
    fun `returns null when beyond two kilometers or invalid coordinates`() {
        val home = place("home", "Nhà", 10.7769, 106.7009, radiusMeters = 150.0)
        assertNull(findMatchingPlace(10.82, 106.7009, listOf(home)))
        assertNull(findMatchingPlace(Double.NaN, 106.7009, listOf(home)))
        assertNull(findMatchingPlace(100.0, 106.7009, listOf(home)))
        assertNull(findMatchingPlace(null, 106.7009, listOf(home)))
    }
}
