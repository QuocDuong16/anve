package com.anve.app.ui.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale

@Composable
internal fun LargeFontScale(enabled: Boolean = true, content: @Composable () -> Unit) {
    if (enabled) {
        DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.3f), content)
    } else {
        content()
    }
}
