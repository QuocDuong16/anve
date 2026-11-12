package com.anve.app.data.circles

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class IncomingInvitation(val id: String, val code: String?, val receivedAt: Long, val owner: String?) {
    override fun toString(): String = "IncomingInvitation(redacted)"
}

/** Process memory only: a link survives rotation/sign-in, never backup or process death. */
class InvitationInbox(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val pending = MutableStateFlow<IncomingInvitation?>(null)
    val invitation = pending.asStateFlow()
    private var account: String? = null

    @Synchronized fun offer(raw: String) {
        pending.value = IncomingInvitation(UUID.randomUUID().toString(), InviteLink.parse(raw), now(), account)
    }

    @Synchronized fun accountChanged(owner: String?) {
        account = owner
        val current = pending.value ?: return
        when {
            expired(current) -> pending.value = null
            current.owner != null && current.owner != owner -> pending.value = null
            current.owner == null && owner != null -> pending.value = current.copy(owner = owner)
        }
    }

    @Synchronized fun current(id: String, owner: String): IncomingInvitation? {
        val current = pending.value ?: return null
        if (expired(current)) { pending.value = null; return null }
        return current.takeIf { it.id == id && it.owner == owner && account == owner }
    }

    @Synchronized fun consume(id: String) { if (pending.value?.id == id) pending.value = null }

    private fun expired(value: IncomingInvitation): Boolean = now() - value.receivedAt !in 0 until 15 * 60_000L
}
