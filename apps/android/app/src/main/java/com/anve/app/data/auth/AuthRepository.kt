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

private data class SignOutPlan(
    val session: StoredSession?,
    val deferredForConsent: Boolean,
)

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
    private val retryScheduler: PendingRevocationScheduler = NoOpPendingRevocationScheduler,
    private val consentMutations: ConsentMutationCoordinator = ConsentMutationCoordinator(),
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
        val plan = consentMutations.withLocalMutation {
            val current = storage.current()
            if (current == null) return@withLocalMutation SignOutPlan(null, false)
            val owner = current.userId
            val pendingConsent = owner?.let { ownerId ->
                storage.pendingConsentOperations().firstOrNull { it.ownerUserId == ownerId && !it.desiredEnabled }
            }
            if (pendingConsent != null) {
                storage.savePendingConsentOperation(
                    pendingConsent.copy(
                        refreshToken = current.refreshToken,
                        credentialExpiresAtMillis = now() + PENDING_CONSENT_CREDENTIAL_TTL_MILLIS,
                        signOutAfterConsent = true,
                        requiresOwnerAuthentication = false,
                    ),
                )
                notices.publish(SessionNoticeKind.LocalSignOutRetryScheduled)
                storage.clearIfMatches(owner, current.accessToken)
                SignOutPlan(null, true)
            } else {
                SignOutPlan(current, false)
            }
        }
        if (plan.deferredForConsent) {
            retryScheduler.ensureScheduled()
            return SignOutOutcome.LocalOnly
        }
        val current = plan.session ?: return SignOutOutcome.LocalOnly
        val outcome = try {
            api.signOut(current.userId).orThrow()
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
            if (e.code == "UNAUTHENTICATED" &&
                storage.current()?.let { it.userId == current.userId } != true
            ) {
                SignOutOutcome.ServerConfirmed
            } else {
                SignOutOutcome.LocalOnly
            }
        } catch (e: IOException) {
            SignOutOutcome.LocalOnly
        }
        if (outcome == SignOutOutcome.LocalOnly) {
            // The interceptor may have rotated the pair before the retried
            // sign-out failed. Queue the newest token — the captured one is
            // already dead in that case — so a later retry can still revoke
            // the session.
            val latest = storage.current()?.takeIf { it.userId == current.userId } ?: current
            val queued = consentMutations.withLocalMutation {
                val saved = savePendingRevocation(
                    newPendingRevocation(
                        refreshToken = latest.refreshToken,
                        nowMillis = now(),
                        ownerUserId = current.userId,
                    ),
                )
                if (saved) notices.publish(SessionNoticeKind.LocalSignOutRetryScheduled)
                storage.clearIfMatches(current.userId, latest.accessToken)
                saved
            }
            if (!queued) notices.publish(SessionNoticeKind.LocalSignOutRetryUnavailable)
            if (queued) retryScheduler.ensureScheduled()
        }
        if (outcome == SignOutOutcome.ServerConfirmed) {
            val latest = storage.current()?.takeIf { it.userId == current.userId }
            storage.clearIfMatches(current.userId, latest?.accessToken ?: current.accessToken)
        }
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
     *
     * Returning `true` means at least one record remains (offline, backoff,
     * server error), so the caller — the WorkManager worker or the app-start
     * pass — keeps a constrained retry scheduled. A completed pass returns
     * `false` and the unique work finishes.
     */
    suspend fun retryPendingRevocations(): Boolean = retryMutex.withLock {
        val records = try {
            storage.pendingRevocations()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withLock false
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
        val consentRecords = try {
            storage.pendingConsentOperations()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val activeOwner = storage.current()?.userId
        for (operation in consentRecords) {
            if (now() < operation.nextAttemptAtMillis) continue
            if (operation.requiresOwnerAuthentication &&
                (activeOwner != operation.ownerUserId ||
                    operation.lastErrorCode == "CONSENT_VERSION_CONFLICT" ||
                    operation.lastErrorCode == "CONSENT_OPERATION_EXPIRED")
            ) continue
            try {
                retryPendingConsentOperation(operation)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                rescheduleConsentOperation(operation)
            }
        }
        try {
            val remainingRevocations = storage.pendingRevocations().isNotEmpty()
            val remainingConsent = storage.pendingConsentOperations().any {
                !it.hasConsentConflict && (!it.requiresOwnerAuthentication ||
                    (storage.current()?.userId == it.ownerUserId &&
                        it.lastErrorCode != "CONSENT_VERSION_CONFLICT" &&
                        it.lastErrorCode != "CONSENT_OPERATION_EXPIRED"))
            }
            remainingRevocations || remainingConsent
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
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

        if (record.ownerUserId != null) {
            val owner = try {
                plainApi.meWithAuthorization("Bearer ${tokens.accessToken}", record.ownerUserId).orThrow()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                reschedule(rotated)
                return
            }
            if (owner.id != record.ownerUserId) {
                storage.removePendingRevocation(record.id)
                return
            }
        }

        val revoked = try {
            plainApi.signOutWithAuthorization("Bearer ${tokens.accessToken}", record.ownerUserId)
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
        // attemptCount counts completed failures, so the first retry must
        // use delay index 0 (30s) rather than 1 (60s).
        savePendingRevocation(
            record.copy(
                attemptCount = attempts,
                nextAttemptAtMillis = now() + retryDelayMillis(attempts - 1),
            ),
        )
        retryScheduler.ensureScheduled()
    }

    private suspend fun retryPendingConsentOperation(original: PendingConsentOperation) {
        val currentOperation = storage.pendingConsentOperations()
            .firstOrNull { it.operationId == original.operationId } ?: return
        var operation = currentOperation
        consentMutations.restoreBlockedAdmission(operation.ownerUserId, operation.generation)
        if (operation.hasConsentConflict) return
        var explicitAccessToken: String? = null
        val active = storage.current()?.takeIf { it.userId == operation.ownerUserId }

        if (operation.refreshToken != null) {
            val refreshed = try {
                plainApi.refresh(RefreshRequestDto(operation.refreshToken))
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                rescheduleConsentOperation(operation)
                return
            }
            if (!refreshed.isSuccessful) {
                if (refreshed.code() == 401) {
                    saveConsentOperationIfCurrent(operation) {
                        it.copy(
                            refreshToken = null,
                            credentialExpiresAtMillis = null,
                            requiresOwnerAuthentication = true,
                            lastErrorCode = "UNAUTHENTICATED",
                        )
                    }
                } else {
                    rescheduleConsentOperation(operation)
                }
                return
            }
            val tokens = refreshed.body()?.session
            if (tokens == null) {
                rescheduleConsentOperation(operation)
                return
            }
            operation = operation.copy(
                refreshToken = tokens.refreshToken,
                attemptCount = 0,
                nextAttemptAtMillis = 0,
                requiresOwnerAuthentication = false,
                lastErrorCode = null,
            )
            val stillLatest = consentMutations.withLocalMutation {
                val latest = storage.pendingConsentOperations().firstOrNull {
                    it.ownerUserId == operation.ownerUserId
                }
                when {
                    latest == null -> false
                    latest.operationId == operation.operationId -> {
                        storage.savePendingConsentOperation(operation)
                        true
                    }
                    !latest.desiredEnabled && !operation.desiredEnabled -> {
                        operation = latest.copy(
                            refreshToken = tokens.refreshToken,
                            credentialExpiresAtMillis = currentOperation.credentialExpiresAtMillis,
                            requiresOwnerAuthentication = false,
                            signOutAfterConsent = latest.signOutAfterConsent || operation.signOutAfterConsent,
                        )
                        storage.savePendingConsentOperation(operation)
                        true
                    }
                    else -> {
                        // A newer explicit ON superseded the OFF intent while
                        // its narrow logout credential was rotating. Preserve
                        // the independent session-revocation request without
                        // using that credential to enable sharing.
                        if (currentOperation.signOutAfterConsent) {
                            val credentialExpiry = currentOperation.credentialExpiresAtMillis ?: now()
                            storage.savePendingRevocation(
                                PendingRevocation(
                                    id = "consent-${currentOperation.operationId}",
                                    refreshToken = tokens.refreshToken,
                                    recordedAtMillis = credentialExpiry - PENDING_REVOCATION_TTL_MILLIS,
                                    expiresAtMillis = credentialExpiry,
                                    ownerUserId = currentOperation.ownerUserId,
                                ),
                            )
                        }
                        false
                    }
                }
            }
            if (!stillLatest) {
                retryScheduler.ensureScheduled()
                return
            }
            explicitAccessToken = tokens.accessToken
        } else if (active == null) {
            saveConsentOperationIfCurrent(operation) {
                it.copy(
                    requiresOwnerAuthentication = true,
                    lastErrorCode = operation.lastErrorCode ?: "OWNER_AUTH_REQUIRED",
                )
            }
            return
        }

        if (!operation.consentConfirmed) {
            if (explicitAccessToken != null) {
                val authenticatedOwner = try {
                    meWithAuthorization(explicitAccessToken, operation.ownerUserId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    rescheduleConsentOperation(operation, "OWNER_AUTHENTICATION_FAILED")
                    return
                }
                if (authenticatedOwner.id != operation.ownerUserId) {
                    saveConsentOperationIfCurrent(operation) {
                        it.copy(requiresOwnerAuthentication = true, lastErrorCode = "OWNER_MISMATCH")
                    }
                    return
                }
            }
            val requestAuthorized = consentMutations.authorizeConsentRequest {
                consentMutations.isCurrent(operation.ownerUserId, operation.generation) &&
                    storage.pendingConsentOperations().any {
                        it.ownerUserId == operation.ownerUserId && it.operationId == operation.operationId
                    }
            }
            if (!requestAuthorized) return
            val user = try {
                if (explicitAccessToken != null) {
                    setLocationSharingWithAuthorization(
                        enabled = operation.desiredEnabled,
                        expectedVersion = operation.expectedVersion,
                        ownerUserId = operation.ownerUserId,
                        accessToken = explicitAccessToken,
                        operationId = operation.operationId,
                    )
                } else {
                    setLocationSharing(
                        enabled = operation.desiredEnabled,
                        expectedVersion = operation.expectedVersion,
                        expectedOwnerUserId = operation.ownerUserId,
                        expectedOperationId = operation.operationId,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.code == "CONSENT_VERSION_CONFLICT") {
                    val latest = try {
                        if (explicitAccessToken != null) {
                            meWithAuthorization(explicitAccessToken, operation.ownerUserId)
                        } else {
                            me()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (latest == null) {
                        rescheduleConsentOperation(operation, "CONSENT_RECONCILE_FAILED")
                        return
                    }
                    if (latest.id != operation.ownerUserId) {
                        saveConsentOperationIfCurrent(operation) {
                            it.copy(
                                requiresOwnerAuthentication = true,
                                lastErrorCode = "OWNER_MISMATCH",
                            )
                        }
                        return
                    }
                    if (latest.locationSharingEnabled == operation.desiredEnabled) {
                        latest
                    } else {
                        // A worker can resume after process recreation with a
                        // fresh coordinator. Keep this device fail-closed even
                        // when the authoritative account choice conflicts.
                        consentMutations.closeLocationAdmission(operation.ownerUserId)
                        consentMutations.withLocalMutation {
                            val current = storage.pendingConsentOperations().firstOrNull {
                                it.ownerUserId == operation.ownerUserId
                            }
                            if (current?.operationId != operation.operationId) {
                                false
                            } else {
                                storage.savePendingConsentOperation(
                                    current.copy(
                                        conflictServerEnabled = latest.locationSharingEnabled,
                                        conflictServerVersion = latest.locationSharingVersion,
                                        conflictObservedAtMillis = now(),
                                        lastErrorCode = "CONSENT_VERSION_CONFLICT",
                                    ),
                                )
                                storage.updateConsent(
                                    latest.id,
                                    latest.locationSharingEnabled,
                                    latest.locationSharingVersion,
                                )
                                true
                            }
                        }
                        return
                    }
                } else if (e.code == "UNAUTHENTICATED") {
                    saveConsentOperationIfCurrent(operation) {
                        it.copy(
                            refreshToken = null,
                            credentialExpiresAtMillis = null,
                            requiresOwnerAuthentication = true,
                            lastErrorCode = "UNAUTHENTICATED",
                        )
                    }
                    return
                } else {
                    rescheduleConsentOperation(operation, e.code)
                    return
                }
            } catch (_: IOException) {
                rescheduleConsentOperation(operation, "NETWORK")
                return
            }
            if (user.id != operation.ownerUserId) {
                saveConsentOperationIfCurrent(operation) {
                    it.copy(requiresOwnerAuthentication = true, lastErrorCode = "OWNER_MISMATCH")
                }
                return
            }
            val confirmed = consentMutations.withLocalMutation {
                val latest = storage.pendingConsentOperations().firstOrNull {
                    it.ownerUserId == operation.ownerUserId
                }
                if (latest?.operationId != operation.operationId ||
                    !consentMutations.isCurrent(operation.ownerUserId, operation.generation)
                ) {
                    false
                } else {
                    storage.updateConsent(user.id, user.locationSharingEnabled, user.locationSharingVersion)
                    operation = operation.copy(consentConfirmed = true)
                    storage.savePendingConsentOperation(operation)
                    if (user.locationSharingEnabled) {
                        consentMutations.openLocationAdmission(operation.ownerUserId, operation.generation)
                    }
                    true
                }
            }
            if (!confirmed) return
        }

        if (operation.signOutAfterConsent) {
            val result = try {
                if (explicitAccessToken != null) {
                    plainApi.signOutWithAuthorization("Bearer $explicitAccessToken", operation.ownerUserId)
                } else {
                    api.signOut(operation.ownerUserId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                rescheduleConsentOperation(operation, "NETWORK")
                return
            }
            if (!result.isSuccessful) {
                if (result.code() == 401 && storage.current()?.userId != operation.ownerUserId) {
                    storage.removePendingConsentOperation(operation.operationId)
                } else {
                    rescheduleConsentOperation(operation, "SIGNOUT_${result.code()}")
                }
                return
            }
        }
        storage.removePendingConsentOperation(operation.operationId)
    }

    private suspend fun rescheduleConsentOperation(
        operation: PendingConsentOperation,
        errorCode: String? = operation.lastErrorCode,
    ) {
        val attempts = operation.attemptCount + 1
        val saved = consentMutations.withLocalMutation {
            val latest = storage.pendingConsentOperations().firstOrNull {
                it.ownerUserId == operation.ownerUserId
            }
            if (latest?.operationId != operation.operationId) {
                false
            } else {
                storage.savePendingConsentOperation(
                    latest.copy(
                        attemptCount = attempts,
                        nextAttemptAtMillis = now() + retryDelayMillis(attempts - 1),
                        lastErrorCode = errorCode,
                    ),
                )
                true
            }
        }
        if (saved) retryScheduler.ensureScheduled()
    }

    private suspend fun saveConsentOperationIfCurrent(
        operation: PendingConsentOperation,
        update: (PendingConsentOperation) -> PendingConsentOperation,
    ): Boolean = consentMutations.withLocalMutation {
        val latest = storage.pendingConsentOperations().firstOrNull {
            it.ownerUserId == operation.ownerUserId
        }
        if (latest?.operationId != operation.operationId) {
            false
        } else {
            storage.savePendingConsentOperation(update(latest))
            true
        }
    }

    suspend fun me(): UserDto {
        val before = storage.current()
        val user = api.me().orThrow()
        consentMutations.withLocalMutation {
            if (before?.userId == null && before != null) {
                storage.setUserIdIfMatches(before.accessToken, user.id)
            }
            val pending = storage.pendingConsentOperations().any { it.ownerUserId == user.id }
            if (!pending) storage.updateConsent(user.id, user.locationSharingEnabled, user.locationSharingVersion)
        }
        return user
    }

    suspend fun pendingConsentOperation(ownerUserId: String): PendingConsentOperation? =
        storage.pendingConsentOperations().firstOrNull { it.ownerUserId == ownerUserId }

    suspend fun updateDisplayName(displayName: String): UserDto =
        api.updateMe(UpdateMeRequestDto(displayName)).orThrow()

    suspend fun setLocationSharing(
        enabled: Boolean,
        expectedVersion: Long? = null,
        expectedOwnerUserId: String? = null,
        expectedOperationId: String? = null,
    ): UserDto {
        val before = storage.current() ?: throw IOException("There is no active session")
        val authenticatedOwner = before.userId ?: me().id
        val owner = expectedOwnerUserId ?: authenticatedOwner
        if (authenticatedOwner != owner) {
            throw IOException("The authenticated account does not match the consent owner")
        }
        if (before.userId != null && before.userId != owner) {
            throw IOException("The active account changed before the consent request")
        }
        if (before.userId == null) storage.setUserIdIfMatches(before.accessToken, owner)

        val operation = expectedOperationId?.let { operationId ->
            storage.pendingConsentOperations().firstOrNull {
                it.ownerUserId == owner && it.operationId == operationId && it.desiredEnabled == enabled
            } ?: throw IOException("Consent operation was superseded")
        }
        val generation = operation?.generation ?: run {
            if (!enabled) consentMutations.closeLocationAdmission(owner)
            consentMutations.nextGeneration(owner)
        }
        if (operation != null) {
            consentMutations.restoreBlockedAdmission(owner, generation)
        }

        val baseVersion = expectedVersion ?: before.locationSharingVersion ?: me().locationSharingVersion
        val requestAuthorized = consentMutations.authorizeConsentRequest {
            consentMutations.isCurrent(owner, generation) &&
                (operation == null || storage.pendingConsentOperations().any {
                    it.ownerUserId == owner && it.operationId == operation.operationId
                })
        }
        if (!requestAuthorized) throw IOException("Consent operation was superseded")

        val user = api.setLocationSharing(
            LocationSharingRequestDto(enabled = enabled, expectedVersion = baseVersion),
            expectedOwner = owner,
        ).orThrow()
        if (user.id != owner) throw IOException("The consent response belongs to another account")
        consentMutations.withLocalMutation {
            val pending = storage.pendingConsentOperations().any { it.ownerUserId == owner }
            val currentOwner = storage.current()?.userId
            if (!pending && currentOwner == owner && consentMutations.isCurrent(owner, generation)) {
                storage.updateConsent(owner, user.locationSharingEnabled, user.locationSharingVersion)
                if (user.locationSharingEnabled) {
                    consentMutations.openLocationAdmission(owner, generation)
                }
            }
        }
        return user
    }

    suspend fun meWithAuthorization(accessToken: String, ownerUserId: String): UserDto =
        plainApi.meWithAuthorization("Bearer $accessToken", ownerUserId).orThrow()

    suspend fun setLocationSharingWithAuthorization(
        enabled: Boolean,
        expectedVersion: Long,
        ownerUserId: String,
        accessToken: String,
        operationId: String,
    ): UserDto {
        val operation = storage.pendingConsentOperations().firstOrNull {
            it.ownerUserId == ownerUserId && it.operationId == operationId &&
                it.desiredEnabled == enabled
        } ?: throw IOException("Consent operation was superseded")
        consentMutations.restoreBlockedAdmission(ownerUserId, operation.generation)
        val requestAuthorized = consentMutations.authorizeConsentRequest {
            consentMutations.isCurrent(ownerUserId, operation.generation) &&
                storage.pendingConsentOperations().any {
                    it.ownerUserId == ownerUserId && it.operationId == operationId
                }
        }
        if (!requestAuthorized) throw IOException("Consent operation was superseded")
        val user = plainApi.setLocationSharingWithAuthorization(
            authorization = "Bearer $accessToken",
            expectedOwner = ownerUserId,
            body = LocationSharingRequestDto(enabled, expectedVersion),
        ).orThrow()
        if (user.id != ownerUserId) throw IOException("The consent response belongs to another account")
        consentMutations.withLocalMutation {
            val pending = storage.pendingConsentOperations().any { it.ownerUserId == ownerUserId }
            if (!pending && consentMutations.isCurrent(ownerUserId, operation.generation)) {
                storage.updateConsent(ownerUserId, user.locationSharingEnabled, user.locationSharingVersion)
                if (user.locationSharingEnabled) {
                    consentMutations.openLocationAdmission(ownerUserId, operation.generation)
                }
            }
        }
        return user
    }

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
        val current = storage.current()
        api.deleteMe(DeleteAccountRequestDto(confirm = true)).orThrow()
        current?.userId?.let { storage.removePendingConsentOperations(it) }
        if (current != null) storage.clearIfMatches(current.userId, current.accessToken)
        notices.clear()
    }

    private suspend fun persist(response: SignInResponseDto) {
        storage.save(
            StoredSession(
                accessToken = response.session.accessToken,
                refreshToken = response.session.refreshToken,
                accessExpiresAt = response.session.accessExpiresAt,
                refreshExpiresAt = response.session.refreshExpiresAt,
                userId = response.user.id,
                locationSharingEnabled = response.user.locationSharingEnabled,
                locationSharingVersion = response.user.locationSharingVersion,
            ),
        )
        // A new account must never inherit the previous sign-out message.
        notices.clear()
        if (storage.pendingConsentOperations().any { it.ownerUserId == response.user.id }) {
            retryScheduler.ensureScheduled()
        }
    }
}

/**
 * Exponential backoff for the revocation retry: 30s, 1m, 2m… capped at six
 * hours. [attemptCount] is zero-based: 0 means "the first retry after the
 * first failure". The record still expires at its original deadline, so
 * backoff can never extend the retention beyond
 * [PENDING_REVOCATION_TTL_MILLIS].
 */
internal fun retryDelayMillis(attemptCount: Int): Long {
    val doublings = attemptCount.coerceIn(0, 16)
    return minOf(PENDING_RETRY_BASE_DELAY_MILLIS shl doublings, PENDING_RETRY_MAX_DELAY_MILLIS)
}
