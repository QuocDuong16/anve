package com.anve.app.data.auth

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.AuthConfigDto
import com.anve.app.data.api.DeleteAccountRequestDto
import com.anve.app.data.api.DevLoginRequestDto
import com.anve.app.data.api.DeviceListDto
import com.anve.app.data.api.GoogleSignInRequestDto
import com.anve.app.data.api.LocationSharingRequestDto
import com.anve.app.data.api.SignInResponseDto
import com.anve.app.data.api.UpdateMeRequestDto
import com.anve.app.data.api.UserDto
import com.anve.app.data.api.orThrow
import kotlinx.coroutines.flow.Flow

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

    suspend fun signOut() {
        try {
            api.signOut().orThrow()
        } finally {
            storage.clear()
        }
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

    suspend fun deleteAccount() {
        try {
            api.deleteMe(DeleteAccountRequestDto(confirm = true)).orThrow()
        } finally {
            storage.clear()
        }
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
