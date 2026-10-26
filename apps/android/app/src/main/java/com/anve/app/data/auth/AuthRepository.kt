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
import java.io.IOException

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
     * pending-revocation record retries the revocation later.
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
) {

    val session: Flow<StoredSession?> = storage.session

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
     * When it did not, a sealed pending-revocation record retries the
     * revocation on a later launch (bounded by
     * [PENDING_REVOCATION_TTL_MILLIS]).
     */
    suspend fun signOut(): SignOutOutcome {
        val current = storage.current()
        val outcome = try {
            api.signOut().orThrow()
            SignOutOutcome.ServerConfirmed
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // A rejected session is already dead: nothing left to revoke.
            if (e.code == "UNAUTHENTICATED") SignOutOutcome.ServerConfirmed else SignOutOutcome.LocalOnly
        } catch (e: IOException) {
            SignOutOutcome.LocalOnly
        }
        if (outcome == SignOutOutcome.LocalOnly && current != null) {
            storage.savePendingRevocation(
                PendingRevocation(refreshToken = current.refreshToken, recordedAtMillis = now()),
            )
        }
        storage.clear()
        return outcome
    }

    /**
     * Best-effort completion of an undelivered sign-out. Rotates the old
     * refresh token once, then revokes the session with the new access
     * token. A rejected refresh means the session is already dead; a
     * transport failure keeps the record for the next launch.
     */
    suspend fun retryPendingRevocation() {
        val pending = storage.pendingRevocation() ?: return
        val refreshed = try {
            plainApi.refresh(RefreshRequestDto(pending.refreshToken))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return
        }
        if (!refreshed.isSuccessful) {
            if (refreshed.code() == 401) storage.clearPendingRevocation()
            return
        }
        val tokens = refreshed.body()?.session ?: return
        // Rotation replaced the refresh token; keep the record usable so a
        // failed revocation below can still be retried later.
        storage.savePendingRevocation(
            PendingRevocation(refreshToken = tokens.refreshToken, recordedAtMillis = pending.recordedAtMillis),
        )
        val revoked = try {
            plainApi.signOutWithAuthorization("Bearer ${tokens.accessToken}").orThrow()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            e.code == "UNAUTHENTICATED"
        } catch (e: IOException) {
            false
        }
        if (revoked) storage.clearPendingRevocation()
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
    }
}
