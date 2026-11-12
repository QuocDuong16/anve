package com.anve.app.ui

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import java.io.File

/** Full composed display, including native dialog and sheet windows. Synthetic fixtures only. */
internal fun captureNativeFixture(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("ui-validation"))
    directory.mkdirs()
    File(directory, "$name-font-scale.txt").writeText("androidFontScale=${instrumentation.targetContext.resources.configuration.fontScale}\n")
    val previous = HardwareRendererCompat.isDrawingEnabled()
    try {
        HardwareRendererCompat.setDrawingEnabled(true)
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        File(directory, "$name.png").outputStream().use {
            check(requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally { HardwareRendererCompat.setDrawingEnabled(previous) }
}
