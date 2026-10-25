package com.anve.app.data.circles

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.CircleMembershipDto
import com.anve.app.data.api.CreateCircleRequestDto
import com.anve.app.data.api.CreateInviteRequestDto
import com.anve.app.data.api.InviteDto
import com.anve.app.data.api.LeaveCircleRequestDto
import com.anve.app.data.api.MemberDto
import com.anve.app.data.api.RedeemInviteRequestDto
import com.anve.app.data.api.orThrow

/**
 * Family Circle use-cases. Authorization lives on the server; the client
 * only renders what it is allowed to see.
 */
class CircleRepository(
    private val api: AnveApi,
) {

    suspend fun myCircle(): CircleMembershipDto = api.myCircle().orThrow()

    suspend fun create(name: String): CircleMembershipDto =
        api.createCircle(CreateCircleRequestDto(name)).orThrow()

    suspend fun joinWithCode(code: String): CircleMembershipDto =
        api.redeemInvite(RedeemInviteRequestDto(code)).orThrow()

    suspend fun members(circleId: String): List<MemberDto> =
        api.members(circleId).orThrow().members

    suspend fun removeMember(circleId: String, userId: String) {
        api.removeMember(circleId, userId).orThrow()
    }

    suspend fun leave(circleId: String, dissolve: Boolean) {
        api.leaveCircle(circleId, LeaveCircleRequestDto(dissolve)).orThrow()
    }

    suspend fun createInvite(circleId: String): InviteDto =
        api.createInvite(circleId, CreateInviteRequestDto()).orThrow()

    suspend fun invites(circleId: String): List<InviteDto> =
        api.invites(circleId).orThrow().invites

    suspend fun revokeInvite(inviteId: String) {
        api.revokeInvite(inviteId).orThrow()
    }
}
