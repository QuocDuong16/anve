package com.anve.app.tracking

/** Platform action for the explicit background-location affordance. */
enum class BackgroundPermissionAction {
    NOT_REQUIRED,
    REQUEST_FOREGROUND,
    REQUEST_RUNTIME_BACKGROUND,
    OPEN_APP_SETTINGS,
}

enum class ForegroundPermissionGrant {
    MISSING,
    APPROXIMATE,
    PRECISE,
}

/** Pure SDK and grant decision, kept separate from Activity Result APIs. */
fun backgroundPermissionAction(
    sdkInt: Int,
    foreground: ForegroundPermissionGrant,
    backgroundGranted: Boolean,
): BackgroundPermissionAction = when {
    sdkInt < 29 -> BackgroundPermissionAction.NOT_REQUIRED
    foreground == ForegroundPermissionGrant.MISSING -> BackgroundPermissionAction.REQUEST_FOREGROUND
    backgroundGranted -> BackgroundPermissionAction.NOT_REQUIRED
    sdkInt == 29 -> BackgroundPermissionAction.REQUEST_RUNTIME_BACKGROUND
    else -> BackgroundPermissionAction.OPEN_APP_SETTINGS
}

fun backgroundPermissionAction(
    sdkInt: Int,
    foregroundGranted: Boolean,
    backgroundGranted: Boolean,
): BackgroundPermissionAction = backgroundPermissionAction(
    sdkInt = sdkInt,
    foreground = if (foregroundGranted) ForegroundPermissionGrant.PRECISE else ForegroundPermissionGrant.MISSING,
    backgroundGranted = backgroundGranted,
)
