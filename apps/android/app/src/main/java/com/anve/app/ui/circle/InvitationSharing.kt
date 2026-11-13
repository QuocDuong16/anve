package com.anve.app.ui.circle

import android.content.Intent
import com.anve.app.data.api.InviteDto
import com.anve.app.data.circles.InviteLink

fun invitationShareIntent(invite: InviteDto): Intent? {
    val link = InviteLink.create(invite.code) ?: return null
    val message = "Mời bạn tham gia gia đình trên AnVề:\n$link\nNếu link không mở, nhập mã: ${invite.code}\nTham gia không tự bật chia sẻ vị trí."
    return Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message), "Gửi lời mời gia đình")
}
