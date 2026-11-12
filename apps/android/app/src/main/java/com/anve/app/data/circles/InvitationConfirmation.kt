package com.anve.app.data.circles

import com.anve.app.data.api.ApiException
import com.anve.app.data.api.CircleMembershipDto
import kotlinx.coroutines.CancellationException

sealed interface InvitationConfirmation {
    data class Joined(val membership: CircleMembershipDto) : InvitationConfirmation
    data class Rejected(val code: String) : InvitationConfirmation
    data object Unknown : InvitationConfirmation
}

/** One explicit POST, then owner-bound readback; no automatic redemption replay. */
suspend fun CircleRepository.confirmInvitation(owner: String, code: String, circleId: String): InvitationConfirmation {
    val failure = try {
        val joined = joinWithCode(owner, code)
        if (joined.circle?.id == circleId && joined.membership?.userId == owner && joined.membership.circleId == circleId) return InvitationConfirmation.Joined(joined)
        null
    } catch (e: CancellationException) { throw e } catch (e: Exception) { e }
    return reconcileInvitation(owner, circleId, failure)
}

/** Also used by the explicit “check again” action after an uncertain join. */
suspend fun CircleRepository.reconcileInvitation(owner: String, circleId: String, failure: Exception? = null): InvitationConfirmation {
    try {
        val mine = myCircle(owner)
        if (mine.circle?.id == circleId && mine.membership?.userId == owner && mine.membership.circleId == circleId) return InvitationConfirmation.Joined(mine)
    } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Preserve uncertainty. */ }
    return if (failure is ApiException && failure.httpStatus in 400..499 && failure.httpStatus !in setOf(408, 429)) {
        InvitationConfirmation.Rejected(failure.code)
    } else InvitationConfirmation.Unknown
}
