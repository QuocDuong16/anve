package com.anve.app.ui.circle

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.anve.app.R
import com.anve.app.data.api.CircleDto
import com.anve.app.data.api.InviteDto
import com.anve.app.ui.captureNativeFixture
import com.anve.app.ui.theme.AnVeTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InvitationSharingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun ownerShareUsesNativeChooserWithCodeFallbackAndMemberHasNoInviteControls() {
        val invite = InviteDto("invite", "circle", "ABCD234567", "owner", Instant.now().plusSeconds(3600).toString(), Instant.now().toString())
        val dark = mutableStateOf(false)
        val role = mutableStateOf("owner")
        var shared: InviteDto? = null
        compose.setContent { AnVeTheme(darkTheme = dark.value) { CircleContent(CircleUiState(loading = false,
            circle = CircleDto("circle", "Nhà mình", "owner", "now"), myRole = role.value, invites = listOf(invite)),
            {}, {}, {}, {}, {}, {}, {}, onShareInvite = { shared = it }) } }
        compose.onNodeWithTag("circle-content").performScrollToNode(hasText("Chia sẻ link mời"))
        compose.onNodeWithText("Chia sẻ link mời").performClick()
        compose.runOnIdle { assertEquals(invite, shared) }
        captureNativeFixture("invitation-share-light")
        compose.runOnIdle { dark.value = true }
        compose.waitForIdle()
        captureNativeFixture("invitation-share-dark")
        val chooser = requireNotNull(
            invitationShareIntent(
                invite,
                message = "Mời bạn tham gia gia đình trên AnVề:\n%1\$s\nNếu link không mở, nhập mã: %2\$s\nTham gia không tự bật chia sẻ vị trí.",
                chooserTitle = "Gửi lời mời gia đình",
            ),
        )
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        val text = send.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text.contains("anve://invite/ABCD234567"))
        assertTrue(text.contains("nhập mã: ABCD234567"))
        assertFalse(text.contains("latitude"))
        assertNull(send.data)
        compose.runOnIdle { role.value = "member" }
        compose.onNodeWithText("Chia sẻ link mời").assertDoesNotExist()
    }

    @Test fun revokeInviteNeedsConfirmDialogAndMemberHasNoRevokeButton() {
        val invite = InviteDto("invite", "circle", "ABCD234567", "owner", Instant.now().plusSeconds(3600).toString(), Instant.now().toString())
        val role = mutableStateOf("owner")
        var revoked: List<String> = emptyList()
        compose.setContent { AnVeTheme { CircleContent(CircleUiState(loading = false,
            circle = CircleDto("circle", "Nhà mình", "owner", "now"), myRole = role.value, invites = listOf(invite)),
            {}, {}, {},
            onRevokeInvite = { revoked += it },
            onRemoveMember = {}, onLeave = {}, onRetry = {}) } }
        val revokeLabel = compose.activity.getString(R.string.circle_invite_revoke)
        val revokeTitle = compose.activity.getString(R.string.circle_invite_revoke_title)
        val cancelLabel = compose.activity.getString(R.string.action_cancel)
        compose.onNodeWithTag("circle-content").performScrollToNode(hasText(revokeLabel))
        compose.onNodeWithText(revokeLabel).performClick()
        compose.onNodeWithText(revokeTitle).assertIsDisplayed()
        // Cancel keeps the invite alive: the dialog closes, no callback fires.
        compose.onNodeWithText(cancelLabel).performClick()
        compose.onNodeWithText(revokeTitle).assertDoesNotExist()
        assertEquals(emptyList<String>(), revoked)
        compose.onNodeWithText(revokeLabel).performClick()
        // With the dialog open the label matches two nodes: the row button and
        // the dialog's confirm, enumerated last because the dialog window is
        // collected after the main window. A wrong click here would tap the
        // scrim or the covered row, so the callback assertion below cannot
        // pass by accident.
        compose.onAllNodesWithText(revokeLabel).assertCountEquals(2)
        compose.onAllNodesWithText(revokeLabel)[1].performClick()
        assertEquals(listOf(invite.id), revoked)
        compose.runOnIdle { role.value = "member" }
        compose.onNodeWithText(revokeLabel).assertDoesNotExist()
    }
}
