package com.anve.app.data.circles

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.InviteDto
import com.anve.app.data.api.PrivateInvitationRequestDto
import com.anve.app.data.api.ReceivedInvitationDto
import com.anve.app.data.api.orThrow
import com.anve.app.data.auth.SessionStorage

interface PrivateInvitationGateway {
    suspend fun create(owner: String, circle: String, request: PrivateInvitationRequestDto): InviteDto
    suspend fun created(owner: String, circle: String, requestId: String): InviteDto
    suspend fun received(owner: String): List<ReceivedInvitationDto>
    suspend fun detail(owner: String, id: String): ReceivedInvitationDto
    suspend fun hide(owner: String, id: String)
}

class PrivateInvitationRepository(private val api: AnveApi, private val sessions: SessionStorage) : PrivateInvitationGateway {
    private suspend fun <T> owned(owner: String, read: suspend () -> T): T {
        check(sessions.current()?.userId == owner)
        val result = read()
        check(sessions.current()?.userId == owner)
        return result
    }
    override suspend fun create(owner: String, circle: String, request: PrivateInvitationRequestDto) = owned(owner) {
        api.createPrivateInvitation(owner, circle, request).orThrow()
    }
    override suspend fun created(owner: String, circle: String, requestId: String) = owned(owner) {
        api.createdPrivateInvitation(owner, circle, requestId).orThrow()
    }
    override suspend fun received(owner: String) = owned(owner) {
        api.receivedInvitations(owner).orThrow().invitations.also { require(it.size <= 20); it.forEach { entry -> entry.checked() } }
    }
    override suspend fun detail(owner: String, id: String) = owned(owner) {
        api.receivedInvitation(owner, id).orThrow().checked().also { require(it.id == id && InviteLink.create(it.code.orEmpty()) != null) }
    }
    override suspend fun hide(owner: String, id: String) { owned(owner) { api.hideReceivedInvitation(owner, id).orThrow() } }
}
