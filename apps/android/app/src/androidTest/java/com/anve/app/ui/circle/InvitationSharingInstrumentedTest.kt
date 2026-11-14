package com.anve.app.ui.circle

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
}
