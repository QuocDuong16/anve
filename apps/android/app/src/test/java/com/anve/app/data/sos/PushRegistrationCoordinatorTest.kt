package com.anve.app.data.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRegistrationCoordinatorTest {
    private class FakeScheduler : PushTokenWorkScheduler {
        data class Call(val owner: String, val register: Boolean, val replace: Boolean)
        val calls = mutableListOf<Call>()

        override fun scheduleRegister(owner: String, replace: Boolean) {
            calls.add(Call(owner, register = true, replace = replace))
        }

        override fun scheduleUnregister(owner: String) {
            calls.add(Call(owner, register = false, replace = true))
        }
    }

    @Test
    fun `auto schedules on sign in when notifications are already enabled`() {
        val scheduler = FakeScheduler()
        val coordinator = PushRegistrationCoordinator(
            notificationsEnabled = { true },
            isFirebaseConfigured = { true },
            scheduler = scheduler,
        )

        coordinator.onSessionChanged("user-a")
        coordinator.syncIfPermitted("user-a")

        assertEquals(
            listOf(FakeScheduler.Call("user-a", register = true, replace = false)),
            scheduler.calls,
        )
    }

    @Test
    fun `skips scheduling until notification permission is granted`() {
        var enabled = false
        val scheduler = FakeScheduler()
        val coordinator = PushRegistrationCoordinator(
            notificationsEnabled = { enabled },
            isFirebaseConfigured = { true },
            scheduler = scheduler,
        )

        coordinator.onSessionChanged("user-a")
        assertTrue(scheduler.calls.isEmpty())

        enabled = true
        coordinator.onPermissionGranted("user-a")

        assertEquals(
            listOf(FakeScheduler.Call("user-a", register = true, replace = true)),
            scheduler.calls,
        )
    }

    @Test
    fun `syncs automatically when notifications are enabled in system settings on resume`() {
        var enabled = false
        val scheduler = FakeScheduler()
        val coordinator = PushRegistrationCoordinator(
            notificationsEnabled = { enabled },
            isFirebaseConfigured = { true },
            scheduler = scheduler,
        )

        coordinator.onSessionChanged("user-a")
        assertFalse(coordinator.syncIfPermitted("user-a"))

        enabled = true
        assertTrue(coordinator.syncIfPermitted("user-a"))
        assertFalse(coordinator.syncIfPermitted("user-a"))

        assertEquals(
            listOf(FakeScheduler.Call("user-a", register = true, replace = false)),
            scheduler.calls,
        )
    }

    @Test
    fun `does not schedule when firebase build config is missing`() {
        val scheduler = FakeScheduler()
        val coordinator = PushRegistrationCoordinator(
            notificationsEnabled = { true },
            isFirebaseConfigured = { false },
            scheduler = scheduler,
        )

        coordinator.onSessionChanged("user-a")
        coordinator.onPermissionGranted("user-a")

        assertTrue(scheduler.calls.isEmpty())
    }

    @Test
    fun `account switch and re-sign-in schedule fresh registration`() {
        val scheduler = FakeScheduler()
        val coordinator = PushRegistrationCoordinator(
            notificationsEnabled = { true },
            isFirebaseConfigured = { true },
            scheduler = scheduler,
        )

        coordinator.onSessionChanged("user-a")
        coordinator.onSessionChanged(null)
        coordinator.onSessionChanged("user-a")
        coordinator.onSessionChanged("user-b")

        assertEquals(
            listOf(
                FakeScheduler.Call("user-a", register = true, replace = false),
                FakeScheduler.Call("user-a", register = true, replace = false),
                FakeScheduler.Call("user-b", register = true, replace = false),
            ),
            scheduler.calls,
        )
    }

    @Test
    fun `disabling notifications after registration schedules unregister and re-enabling re-registers`() {
        var enabled = true
        val scheduler = FakeScheduler()
        val coordinator = PushRegistrationCoordinator(
            notificationsEnabled = { enabled },
            isFirebaseConfigured = { true },
            scheduler = scheduler,
        )

        coordinator.onSessionChanged("user-a")
        coordinator.onWorkerSucceeded("user-a", registered = true)

        enabled = false
        assertFalse(coordinator.syncIfPermitted("user-a"))

        enabled = true
        assertTrue(coordinator.syncIfPermitted("user-a"))

        assertEquals(
            listOf(
                FakeScheduler.Call("user-a", register = true, replace = false),
                FakeScheduler.Call("user-a", register = false, replace = true),
                FakeScheduler.Call("user-a", register = true, replace = false),
            ),
            scheduler.calls,
        )
    }

    @Test
    fun `permanent worker failure allows subsequent retry on resume`() {
        val scheduler = FakeScheduler()
        val coordinator = PushRegistrationCoordinator(
            notificationsEnabled = { true },
            isFirebaseConfigured = { true },
            scheduler = scheduler,
        )

        coordinator.onSessionChanged("user-a")
        coordinator.onWorkerPermanentFailure("user-a")
        assertTrue(coordinator.syncIfPermitted("user-a"))

        assertEquals(2, scheduler.calls.size)
    }

    @Test
    fun `token refresh forces replacement registration when notifications are enabled`() {
        val scheduler = FakeScheduler()
        val coordinator = PushRegistrationCoordinator(
            notificationsEnabled = { true },
            isFirebaseConfigured = { true },
            scheduler = scheduler,
        )

        coordinator.onSessionChanged("user-a")
        coordinator.onWorkerSucceeded("user-a", registered = true)
        coordinator.onNewToken("user-a")

        assertEquals(
            listOf(
                FakeScheduler.Call("user-a", register = true, replace = false),
                FakeScheduler.Call("user-a", register = true, replace = true),
            ),
            scheduler.calls,
        )
    }
}
