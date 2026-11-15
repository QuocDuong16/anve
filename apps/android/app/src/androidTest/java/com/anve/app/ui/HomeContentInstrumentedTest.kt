package com.anve.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.anve.app.R
import com.anve.app.data.api.DeviceDto
import com.anve.app.data.api.DeviceSessionDto
import com.anve.app.data.api.UserDto
import com.anve.app.ui.home.HomeContent
import com.anve.app.ui.home.HomeUiState
import com.anve.app.ui.theme.AnVeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The other device's id: its session is the one a confirm must revoke. */
private const val otherDeviceId = "d2"

/**
 * Exercises the Tài khoản content in isolation: the current device must not
 * revoke its own session, and every other revocation passes through the
 * confirm dialog. Fake state only — no network and no real session store.
 */
class HomeContentInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun currentDeviceCannotRevokeItselfOtherDeviceRevokesAfterConfirm() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val revokedDeviceIds = mutableListOf<String>()
        compose.setContent {
            AnVeTheme {
                HomeContent(
                    state = HomeUiState(
                        loading = false,
                        user = sampleUser(),
                        devices = listOf(currentDevice(), otherDevice()),
                    ),
                    onToggleSharing = {},
                    onRename = {},
                    onRevokeDevice = { revokedDeviceIds += it },
                    onSignOut = {},
                    onDeleteAccount = {},
                    onRetry = {},
                )
            }
        }

        val revokeLabel = context.getString(R.string.home_device_revoke)
        // Every row carries the same 'Thu hồi phiên' label; index order
        // follows state.devices, and the current device is placed first.
        compose.onAllNodesWithText(revokeLabel).assertCountEquals(2)
        compose.onAllNodesWithText(revokeLabel)[0].assertIsNotEnabled()
        compose.onAllNodesWithText(revokeLabel)[1].assertIsEnabled()

        compose.onAllNodesWithText(revokeLabel)[1].performClick()
        compose.onNodeWithText(context.getString(R.string.home_device_revoke_title)).assertIsDisplayed()
        // Cancel keeps the session on the server.
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.onNodeWithText(context.getString(R.string.home_device_revoke_title)).assertDoesNotExist()
        assertEquals(emptyList<String>(), revokedDeviceIds)

        compose.onAllNodesWithText(revokeLabel)[1].performClick()
        // With the dialog open the label matches three nodes: the two rows
        // and the dialog's confirm button, enumerated last because the dialog
        // window is collected after the main window. A wrong click here would
        // tap the scrim or a covered row, so the callback assertion below
        // cannot pass by accident.
        compose.onAllNodesWithText(revokeLabel).assertCountEquals(3)
        compose.onAllNodesWithText(revokeLabel)[2].performClick()
        assertEquals(listOf(otherDeviceId), revokedDeviceIds)
    }

    /**
     * A result message is a card that stays until dismissed: the 'Đã hiểu'
     * button must be on screen and clicking it must call back exactly the
     * consumer the shell wires to the ViewModel.
     */
    @Test fun messageCardOffersDismissThatCallsBack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var dismissed = false
        compose.setContent {
            AnVeTheme {
                HomeContent(
                    state = HomeUiState(
                        loading = false,
                        user = sampleUser(),
                        devices = listOf(currentDevice(), otherDevice()),
                        messageRes = R.string.home_rename_done,
                    ),
                    onToggleSharing = {},
                    onRename = {},
                    onRevokeDevice = {},
                    onSignOut = {},
                    onDeleteAccount = {},
                    onRetry = {},
                    onDismissMessage = { dismissed = true },
                )
            }
        }

        val dismissLabel = context.getString(R.string.action_dismiss)
        compose.onNodeWithText(dismissLabel).assertIsDisplayed()
        compose.onNodeWithText(dismissLabel).performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    /**
     * The consent switch must be operable and readable by a screen reader:
     * the row carries the toggleable semantics (title + state in one focus)
     * and is the only toggleable node because the inner Switch is indicative
     * only. The asserted state lands in semantics, not just in switch color.
     */
    @Test fun consentRowIsTheOnlyToggleableAndAnnouncesTheRequestedState() {
        // One composition: the requested value is state the content reads, so
        // the announced state flips without a second setContent (setContent is
        // one-shot per test activity).
        var requested by mutableStateOf(false)
        var toggledTo: Boolean? = null
        compose.setContent {
            AnVeTheme {
                HomeContent(
                    state = HomeUiState(
                        loading = false,
                        user = sampleUser(),
                        devices = listOf(currentDevice(), otherDevice()),
                        locationSharingRequested = requested,
                    ),
                    onToggleSharing = { toggled -> toggledTo = toggled },
                    onRename = {},
                    onRevokeDevice = {},
                    onSignOut = {},
                    onDeleteAccount = {},
                    onRetry = {},
                )
            }
        }

        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onAllNodes(isToggleable())[0].assertIsOff()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.runOnIdle { assertEquals(true, toggledTo) }

        // The announced state follows the requested value: raising the request
        // flips the same row to on (precedent: HistoryScreenInstrumentedTest
        // asserts selection lands in semantics).
        compose.runOnIdle { requested = true }
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onAllNodes(isToggleable())[0].assertIsOn()
    }

    /**
     * One result batch is one acknowledgment card: the message and consent
     * results are one ViewModel batch (production wires both to
     * HomeViewModel.consumeMessage), so only the first non-null notice shows
     * with a single 'Đã hiểu', and its dismiss calls the consumer that owns
     * the shown notice. The avatar notice has its own controller consumer and
     * shows alone when it is the only one pending.
     */
    @Test fun oneResultCardOffersDismissThatCallsTheShownNoticeConsumer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var messageDismisses = 0
        var avatarDismissed = false
        // One composition: the phase flip below mutates state the content
        // reads instead of calling setContent twice (one-shot per activity).
        var state by mutableStateOf(
            HomeUiState(
                loading = false,
                user = sampleUser(),
                devices = listOf(currentDevice(), otherDevice()),
                messageRes = R.string.home_rename_done,
                // ConsentResult still reports resolved copy, so the consent
                // message stays a passthrough String here.
                consentMessage = "x",
            ),
        )
        compose.setContent {
            AnVeTheme {
                HomeContent(
                    state = state,
                    onToggleSharing = {},
                    onRename = {},
                    onRevokeDevice = {},
                    onSignOut = {},
                    onDeleteAccount = {},
                    onRetry = {},
                    onDismissMessage = { messageDismisses++ },
                    avatarMessageRes = R.string.avatar_updated_notice,
                    onDismissAvatarMessage = { avatarDismissed = true },
                )
            }
        }

        val dismissLabel = context.getString(R.string.action_dismiss)
        // Priority picks the message result: the consent copy and the avatar
        // notice queue behind it, and exactly one 'Đã hiểu' exists.
        compose.onNodeWithText(context.getString(R.string.home_rename_done)).assertIsDisplayed()
        compose.onNodeWithText("x").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.avatar_updated_notice)).assertDoesNotExist()
        compose.onAllNodesWithText(dismissLabel).assertCountEquals(1)
        compose.onAllNodesWithText(dismissLabel)[0].performClick()
        compose.runOnIdle { assertEquals(1, messageDismisses) }
        compose.runOnIdle { assertFalse(avatarDismissed) }

        // Only the avatar notice pending: its card acknowledges through the
        // controller consumer, not the ViewModel batch.
        compose.runOnIdle {
            state = HomeUiState(
                loading = false,
                user = sampleUser(),
                devices = listOf(currentDevice(), otherDevice()),
            )
        }
        compose.onNodeWithText(context.getString(R.string.avatar_updated_notice)).assertIsDisplayed()
        compose.onAllNodesWithText(dismissLabel).assertCountEquals(1)
        compose.onAllNodesWithText(dismissLabel)[0].performClick()
        compose.runOnIdle { assertTrue(avatarDismissed) }
    }

    // Sample data mirrors HomeContentPreview, extended with a second device.

    private fun sampleUser() = UserDto(
        id = "u1",
        email = "me@example.com",
        displayName = "Mai",
        avatarUrl = null,
        locationSharingEnabled = false,
        createdAt = "2026-10-25T10:00:00Z",
    )

    private fun currentDevice() = DeviceDto(
        id = "d1",
        name = "Pixel 8",
        platform = "android",
        appVersion = "0.2.0-debug",
        createdAt = "2026-10-25T10:00:00Z",
        lastSeenAt = "2026-10-25T12:00:00Z",
        session = DeviceSessionDto(
            id = "s1",
            createdAt = "2026-10-25T10:00:00Z",
            lastUsedAt = "2026-10-25T12:00:00Z",
            expiresAt = "2026-11-24T10:00:00Z",
            current = true,
        ),
    )

    private fun otherDevice() = DeviceDto(
        id = otherDeviceId,
        name = "Galaxy A54",
        platform = "android",
        appVersion = "0.2.0-debug",
        createdAt = "2026-10-26T09:00:00Z",
        lastSeenAt = "2026-10-26T11:00:00Z",
        session = DeviceSessionDto(
            id = "s2",
            createdAt = "2026-10-26T09:00:00Z",
            lastUsedAt = "2026-10-26T11:00:00Z",
            expiresAt = "2026-11-25T09:00:00Z",
            current = false,
        ),
    )
}
