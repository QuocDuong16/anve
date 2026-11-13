package com.anve.app

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** CI's fresh debug fixture; no account creation or real provider calls. */
class InvitationIntentInstrumentedTest {
    @Test fun coldAndWarmLinksRemainPendingAcrossRotationWithoutPersistingIntentData() {
        val app = ApplicationProvider.getApplicationContext<AnVeApplication>()
        assumeTrue(runBlocking { app.container.sessionStore.current() } == null)
        app.container.invitationInbox.accountChanged(null)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("anve://invite/ABCD234567"), app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resumed = AtomicReference<CountDownLatch>(CountDownLatch(1))
        val current = AtomicReference<MainActivity>()
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        // ActivityScenario matches the original URI against getIntent(); production
        // deliberately clears that URI before CREATED to avoid retaining the secret.
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && stage == Stage.RESUMED) {
                current.set(activity)
                resumed.get().countDown()
            }
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            app.startActivity(intent)
            assertTrue("cold link activity resumes", resumed.get().await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val activity = current.get()
                assertNull(activity.intent.data)
                assertEquals("ABCD234567", app.container.invitationInbox.invitation.value?.code)
                assertNull(app.container.invitationInbox.invitation.value?.owner)
            }
            val original = app.container.invitationInbox.invitation.value!!.id
            val originalActivity = current.get()
            resumed.set(CountDownLatch(1))
            instrumentation.runOnMainSync { originalActivity.recreate() }
            assertTrue("rotated link activity resumes", resumed.get().await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val activity = current.get()
                assertTrue(activity !== originalActivity)
                assertNull(activity.intent.data)
                assertEquals(original, app.container.invitationInbox.invitation.value?.id)
            }
            val warm = Intent(Intent.ACTION_VIEW, Uri.parse("anve://invite/ABCD234568"), app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            app.startActivity(warm)
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (app.container.invitationInbox.invitation.value?.code != "ABCD234568" && SystemClock.elapsedRealtime() < deadline) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                SystemClock.sleep(20)
            }
            instrumentation.runOnMainSync {
                val activity = current.get()
                assertEquals("ABCD234568", app.container.invitationInbox.invitation.value?.code)
                assertNull(activity.intent.data)
            }
            assertNull(runBlocking { app.container.sessionStore.current() })
            app.container.invitationInbox.invitation.value?.let { app.container.invitationInbox.consume(it.id) }
        } finally {
            instrumentation.runOnMainSync {
                monitor.removeLifecycleCallback(callback)
                current.get()?.finish()
            }
        }
    }

    @Test fun browsableLinkResolvesToMainActivityAndForeignHostsDoNot() {
        val app = ApplicationProvider.getApplicationContext<AnVeApplication>()
        val valid = Intent(Intent.ACTION_VIEW, Uri.parse("anve://invite/ABCD234567")).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app.packageName)
        assertEquals(MainActivity::class.java.name, valid.resolveActivity(app.packageManager)?.className)
        assertNotNull(valid.resolveActivity(app.packageManager))
        val foreign = Intent(Intent.ACTION_VIEW, Uri.parse("anve://other/ABCD234567")).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app.packageName)
        assertNull(foreign.resolveActivity(app.packageManager))
    }
}
