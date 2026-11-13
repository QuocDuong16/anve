package com.anve.app.data.api

import com.squareup.moshi.JsonClass
import java.time.Instant
import java.util.UUID

@JsonClass(generateAdapter = false)
data class PrivateInvitationRequestDto(val requestId: String, val mode: String, val recipientEmail: String? = null, val expiresInHours: Int = 48) {
    override fun toString() = "PrivateInvitationRequestDto(mode=$mode)"
}
@JsonClass(generateAdapter = false)
data class ReceivedInvitationsDto(val invitations: List<ReceivedInvitationDto>)
@JsonClass(generateAdapter = false)
data class ReceivedInvitationDto(val id: String, val circleId: String, val circleName: String, val inviterDisplayName: String,
    val expiresAt: String, val code: String? = null) {
    fun checked(): ReceivedInvitationDto = apply {
        require(UUID.fromString(id).toString() == id && UUID.fromString(circleId).toString() == circleId)
        require(circleName.isNotBlank() && inviterDisplayName.isNotBlank())
        Instant.parse(expiresAt)
    }
    override fun toString() = "ReceivedInvitationDto(private=true)"
}
