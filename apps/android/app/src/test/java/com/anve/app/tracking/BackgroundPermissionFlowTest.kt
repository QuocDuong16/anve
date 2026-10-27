package com.anve.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundPermissionFlowTest {

    @Test
    fun `api 28 does not request background permission`() {
        assertEquals(
            BackgroundPermissionAction.NOT_REQUIRED,
            backgroundPermissionAction(28, foregroundGranted = false, backgroundGranted = false),
        )
    }

    @Test
    fun `api 29 requires foreground before runtime background prompt`() {
        assertEquals(
            BackgroundPermissionAction.REQUEST_FOREGROUND,
            backgroundPermissionAction(29, foregroundGranted = false, backgroundGranted = false),
        )
        assertEquals(
            BackgroundPermissionAction.REQUEST_RUNTIME_BACKGROUND,
            backgroundPermissionAction(29, foregroundGranted = true, backgroundGranted = false),
        )
    }

    @Test
    fun `api 30 and later use app settings for missing background permission`() {
        listOf(30, 33, 34, 35).forEach { sdk ->
            assertEquals(
                BackgroundPermissionAction.REQUEST_FOREGROUND,
                backgroundPermissionAction(sdk, foregroundGranted = false, backgroundGranted = false),
            )
            assertEquals(
                BackgroundPermissionAction.OPEN_APP_SETTINGS,
                backgroundPermissionAction(sdk, foregroundGranted = true, backgroundGranted = false),
            )
            assertEquals(
                BackgroundPermissionAction.NOT_REQUIRED,
                backgroundPermissionAction(sdk, foregroundGranted = true, backgroundGranted = true),
            )
        }
    }

    @Test
    fun `approximate foreground permission is still a foreground grant`() {
        assertEquals(
            BackgroundPermissionAction.OPEN_APP_SETTINGS,
            backgroundPermissionAction(
                34,
                foreground = ForegroundPermissionGrant.APPROXIMATE,
                backgroundGranted = false,
            ),
        )
    }
}
