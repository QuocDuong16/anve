package com.anve.app.ui.circle

import android.content.Intent
import com.anve.app.BuildConfig
import com.anve.app.data.api.InviteDto
import com.anve.app.data.circles.InviteLink

/**
 * Builds the Android sharesheet intent for an invite. The copy is resolved
 * at the screen (string resources) because a plain function cannot resolve
 * resources; the recipient reads this text in the chooser, so the
 * consent-not-automatic fact travels with the link.
 */
fun invitationShareIntent(
    invite: InviteDto,
    message: String,
    chooserTitle: String,
    shareBaseUrl: String = BuildConfig.API_BASE_URL,
): Intent? {
    val link = InviteLink.shareUrl(invite.code, shareBaseUrl) ?: return null
    val body = message.format(link, invite.code)
    return Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), chooserTitle)
}
