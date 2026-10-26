package com.anve.app.data.auth

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.AuthConfigDto
import com.anve.app.data.api.DeleteAccountRequestDto
import com.anve.app.data.api.DevLoginRequestDto
import com.anve.app.data.api.DeviceListDto
import com.anve.app.data.api.GoogleSignInRequestDto
import com.anve.app.data.api.LocationSharingRequestDto
import com.anve.app.data.api.RefreshRequestDto
import com.anve.app.data.api.SignInResponseDto
import com.anve.app.data.api.UpdateMeRequestDto
import com.anve.app.data.api.UserDto
import com.anve.app.data.api.orThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Base delay before a failed pending revocation is retried. */
const val PENDING_RETRY_BASE_DELAY_MILLIS: Long = 30_000

/** Upper bound of the exponential retry delay (6 hours). */
const val PENDING_RETRY_MAX_DELAY_MILLIS: Long = 6L * 60 * 60 * 1000

/**
 * What a sign-out actually achieved. Navigation never depends on this — the
 * session storage flow drives it — but the caller can tell the user whether
 * the backend confirmed the revocation.
 */
enum class SignOutOutcome {
    /** The backend revoked the session (or reported it already dead). */
    ServerConfirmed,

    /**
     * The local session is gone, but the backend could not be reached. The
     * session may stay valid on the server until it expires; a sealed
     * pending-revocation queue entry retries the revocation later.
     */
    LocalOnly,
}

/**
 * Session and account use-cases of the client (the mirror of the server's
 * auth service). Token attachment and the 401 refresh-retry live in
 * [SessionInterceptor], which the container wires into the authenticated
 * API client.
 */
class AuthRepository(
    private val api: AnveApi,
    private val plainApi: AnveApi,
    private val storage: SessionStorage,
    private val deviceIdentity: DeviceRegistrationProvider,
    private val now: () -> Long = System::currentTimeMillis,
    private val notices: SessionNoticeStore = SessionNoticeStore(),
) {

    val session: Flow<StoredSession?> = storage.session

    /**
     * Serializes retry passes so two launches in one process cannot rotate
     * the same refresh token twice. It does not serialize sign-out: queue
     * writes are atomic per record, so a new offline sign-out can land while
     * a pass is running without being lost.
     */
    private val retryMutex = Mutex()

    suspend fun authConfig(): AuthConfigDto = plainApi.authConfig().orThrow()

    /**
     * Exchanges a Google ID token obtained through Credential Manager for an
     * AnVề session. The Google token is used exactly once (ADR 0002).
     */
    suspend fun signInWithGoogleToken(idToken: String): UserDto {
        val response = plainApi.signInWithGoogle(
            GoogleSignInRequestDto(idToken = idToken, device = deviceIdentity.registration()),
        ).orThrow()
        persist(response)
        return response.user
    }

    /** Development-only sign-in (mounted outside production only). */
    suspend fun signInDev(email: String, displayName: String? = null): UserDto {
        val response = plainApi.devLogin(
            DevLoginRequestDto(email = email, displayName = displayName, device = deviceIdentity.registration()),
        ).orThrow()
        persist(response)
        return response.user
    }

    /**
     * Signs this device out. The local session is always removed — signing
     * out is the user's explicit instruction and must work offline — but the
     * server-side revocation is only claimed when the backend confirmed it.
     *
     * An unconfirmed revocation is appended to the durable queue (one record
     * per account, never overwriting another session) and a notice is
     * published *before* the session is cleared, so the message survives the
     * navigation to Sign-In.
     */
    suspend fun signOut(): SignOutOutcome {
        val current = storage.current()
        val outcome = try {
            api.signOut().orThrow()
            SignOutOutcome.ServerConfirmed
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // A 401 is not proof by itself: the access token may simply have
            // expired while the refresh token is still alive. If the
            // interceptor managed to rotate (or the server rejected the
            // refresh and cleared storage), the session is gone; if storage
            // still holds a session, the refresh failed transiently and the
            // revocation stays pending. Never claim a server-side revocation
            // that was not observed.
            if (e.code == "UNAUTHENTICATED" && storage.current() == null) {
                SignOutOutcome.ServerConfirmed
            } else {
                SignOutOutcome.LocalOnly
            }
        } catch (e: IOException) {
            SignOutOutcome.LocalOnly
        }
        if (outcome == SignOutOutcome.LocalOnly && current != null) {
            // The interceptor may have rotated the pair before the retried
            // sign-out failed. Queue the newest token — the captured one is
            // already dead in that case — so a later retry can still revoke
            // the session.
            val refreshToken = storage.current()?.refreshToken ?: current.refreshToken
            val queued = savePendingRevocation(
                newPendingRevocation(refreshToken = refreshToken, nowMillis = now()),
            )
            notices.publish(
                if (queued) {
                    SessionNoticeKind.LocalSignOutRetryScheduled
                } else {
                    SessionNoticeKind.LocalSignOutRetryUnavailable
                },
            )
        }
        storage.clear()
        return outcome
    }

    /**
     * Best-effort completion of every undelivered sign-out. Records are
     * processed independently: a failing record is rescheduled and never
     * blocks the rest of the queue.
     *
     * For each record: rotate the refresh token, persist the rotated token
     * before using it, then revoke the session with the new access token.
     * A rejected refresh means the session is already dead (removed); a
     * transport or server error keeps the record with exponential backoff
     * until its original expiry.
     *
     * This is best-effort by definition: if the device never reconnects, the
     * old session stays alive server-side until its refresh token expires.
     */
    suspend fun retryPendingRevocations() {
        retryMutex.withLock {
            val records = try {
                storage.pendingRevocations()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
            for (record in records) {
                if (now() < record.nextAttemptAtMillis) continue
                try {
                    retryPendingRevocation(record)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Never let one broken record starve the queue.
                    reschedule(record)
                }
            }
        }
    }

    private suspend fun retryPendingRevocation(record: PendingRevocation) {
        val refreshed = try {
            plainApi.refresh(RefreshRequestDto(record.refreshToken))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            reschedule(record)
            return
        }
        if (!refreshed.isSuccessful) {
            // 401: the token is already rotated-out, revoked or expired, so
            // the session cannot be revived; drop the record. Other server
            // errors are transient.
            if (refreshed.code() == 401) {
                storage.removePendingRevocation(record.id)
            } else {
                reschedule(record)
            }
            return
        }
        val tokens = refreshed.body()?.session
        if (tokens == null) {
            reschedule(record)
            return
        }

        // Persist the rotated token before the revocation call. If the
        // process dies in between, the old token is already dead on the
        // server; the next launch gets a 401 and drops the record, which is
        // the documented "cannot confirm revocation" case.
        val rotated = record.copy(refreshToken = tokens.refreshToken)
        if (!savePendingRevocation(rotated)) return

        val revoked = try {
            plainApi.signOutWithAuthorization("Bearer ${tokens.accessToken}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            reschedule(rotated)
            return
        }
        when {
            revoked.isSuccessful || revoked.code() == 401 ->
                storage.removePendingRevocation(record.id)
            else -> reschedule(rotated)
        }
    }

    /** Persists a queue record; storage failures stay best-effort. */
    private suspend fun savePendingRevocation(record: PendingRevocation): Boolean =
        try {
            storage.savePendingRevocation(record)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    private suspend fun reschedule(record: PendingRevocation) {
        val attempts = record.attemptCount + 1
        savePendingRevocation(
            record.copy(
                attemptCount = attempts,
                nextAttemptAtMillis = now() + retryDelayMillis(attempts),
            ),
        )
    }

    suspend fun me(): UserDto = api.me().orThrow()

    suspend fun updateDisplayName(displayName: String): UserDto =
        api.updateMe(UpdateMeRequestDto(displayName)).orThrow()

    /** THE consent switch of the privacy foundation. */
    suspend fun setLocationSharing(enabled: Boolean): UserDto =
        api.setLocationSharing(LocationSharingRequestDto(enabled)).orThrow()

    suspend fun devices(): DeviceListDto = api.devices().orThrow()

    suspend fun revokeDevice(deviceId: String) {
        api.revokeDevice(deviceId).orThrow()
    }

    /**
     * Deletes the account. The local session is cleared only after the
     * server confirmed the deletion: a failed request must leave the
     * account usable and retryable, never fake a signed-out state.
     *
     * A transport failure is an unknown outcome — the server may have
     * deleted the account without the response arriving. The caller
     * reconciles by reading the account again: if it is gone, the session
     * is rejected and [SessionInterceptor] clears the local session.
     */
    suspend fun deleteAccount() {
        api.deleteMe(DeleteAccountRequestDto(confirm = true)).orThrow()
        storage.clear()
        notices.clear()
    }

    private suspend fun persist(response: SignInResponseDto) {
        storage.save(
            StoredSession(
                accessToken = response.session.accessToken,
                refreshToken = response.session.refreshToken,
                accessExpiresAt = response.session.accessExpiresAt,
                refreshExpiresAt = response.session.refreshExpiresAt,
            ),
        )
        // A new account must never inherit the previous sign-out message.
        notices.clear()
    }
}

/**
 * Exponential backoff for the revocation retry: 30s, 1m, 2m… capped at six
 * hours. The record still expires at its original deadline, so backoff can
 * never extend the retention beyond [PENDING_REVOCATION_TTL_MILLIS].
 */
internal fun retryDelayMillis(attemptCount: Int): Long {
    val doublings = attemptCount.coerceIn(0, 16)
    return minOf(PENDING_RETRY_BASE_DELAY_MILLIS shl doublings, PENDING_RETRY_MAX_DELAY_MILLIS)
}
