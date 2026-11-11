package com.anve.app.ui.diagnostics

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

internal class SystemFontScaleRule(private val testMethod: String, private val scale: Float) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            if (description.methodName != testMethod) {
                base.evaluate()
                return
            }
            val previousSetting = shell("settings get system font_scale").trim().toFloatOrNull()?.takeIf { it.isFinite() && it > 0 }
            val previousScale = fontScale()
            var failure: Throwable? = null
            try {
                shell("settings put system font_scale $scale")
                awaitScale(scale)
                base.evaluate()
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    shell(if (previousSetting != null) "settings put system font_scale $previousSetting" else "settings delete system font_scale")
                    awaitScale(previousScale)
                } catch (restoreError: Throwable) {
                    val original = failure
                    if (original != null) original.addSuppressed(restoreError) else throw restoreError
                }
            }
        }
    }

    private fun fontScale() = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale

    private fun awaitScale(expected: Float) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (abs(fontScale() - expected) > 0.01f && SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(10)
        }
        check(abs(fontScale() - expected) <= 0.01f) { "Android font scale did not become $expected" }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
