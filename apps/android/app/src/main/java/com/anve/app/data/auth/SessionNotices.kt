package com.anve.app.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** What the user must be told after a sign-out. */
enum class SessionNoticeKind {
    /** Local sign-out succeeded; the server revocation will be retried. */
    LocalSignOutRetryScheduled,

    /**
     * Local sign-out succeeded, but the device could not even persist the
     * retry. The session may stay valid server-side until it expires.
     */
    LocalSignOutRetryUnavailable,
}

/** One transient, account-free notice identified for lifecycle-safe display. */
data class SessionNotice(val id: Long, val kind: SessionNoticeKind)

/**
 * App-scoped transient notices that must survive the navigation triggered
 * by clearing the session. The sign-out clears storage, which reroutes to
 * the Sign-In screen; a message kept in the Home screen's state would be
 * destroyed before the user could read it.
 *
 * It deliberately carries no email, name or token, so a notice can never
 * leak account data or be mistaken for another account's state. The store is
 * cleared on a successful sign-in, so a new account never inherits an old
 * sign-out message.
 */
class SessionNoticeStore {
    private val nextId = AtomicLong(1)
    private val _notice = MutableStateFlow<SessionNotice?>(null)

    val notice: StateFlow<SessionNotice?> = _notice.asStateFlow()

    fun publish(kind: SessionNoticeKind) {
        _notice.value = SessionNotice(id = nextId.getAndIncrement(), kind = kind)
    }

    /** Dismisses the given notice and leaves a newer one untouched. */
    fun dismiss(id: Long) {
        _notice.update { current -> if (current?.id == id) null else current }
    }

    fun clear() {
        _notice.value = null
    }
}
