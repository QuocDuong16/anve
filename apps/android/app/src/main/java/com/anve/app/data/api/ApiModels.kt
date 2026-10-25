package com.anve.app.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/** Shared Moshi instance — one reflection adapter for the whole app. */
val apiMoshi: Moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

@JsonClass(generateAdapter = false)
data class AuthConfigDto(
    @Json(name = "googleSignIn") val googleSignIn: Boolean,
    @Json(name = "devLogin") val devLogin: Boolean,
)

@JsonClass(generateAdapter = false)
data class DeviceRegistrationDto(
    @Json(name = "clientDeviceId") val clientDeviceId: String,
    @Json(name = "name") val name: String = "",
    @Json(name = "platform") val platform: String = "android",
    @Json(name = "appVersion") val appVersion: String = "",
)

@JsonClass(generateAdapter = false)
data class GoogleSignInRequestDto(
    @Json(name = "idToken") val idToken: String,
    @Json(name = "device") val device: DeviceRegistrationDto,
)

@JsonClass(generateAdapter = false)
data class DevLoginRequestDto(
    @Json(name = "email") val email: String,
    @Json(name = "displayName") val displayName: String?,
    @Json(name = "device") val device: DeviceRegistrationDto,
)

@JsonClass(generateAdapter = false)
data class RefreshRequestDto(
    @Json(name = "refreshToken") val refreshToken: String,
)

@JsonClass(generateAdapter = false)
data class UserDto(
    @Json(name = "id") val id: String,
    @Json(name = "email") val email: String,
    @Json(name = "displayName") val displayName: String,
    @Json(name = "avatarUrl") val avatarUrl: String?,
    @Json(name = "locationSharingEnabled") val locationSharingEnabled: Boolean,
    @Json(name = "createdAt") val createdAt: String,
)

@JsonClass(generateAdapter = false)
data class SessionTokensDto(
    @Json(name = "accessToken") val accessToken: String,
    @Json(name = "refreshToken") val refreshToken: String,
    @Json(name = "accessExpiresAt") val accessExpiresAt: String,
    @Json(name = "refreshExpiresAt") val refreshExpiresAt: String,
)

@JsonClass(generateAdapter = false)
data class SignInResponseDto(
    @Json(name = "user") val user: UserDto,
    @Json(name = "session") val session: SessionTokensDto,
)

@JsonClass(generateAdapter = false)
data class RefreshResponseDto(
    @Json(name = "session") val session: SessionTokensDto,
)

@JsonClass(generateAdapter = false)
data class DeviceSessionDto(
    @Json(name = "id") val id: String,
    @Json(name = "createdAt") val createdAt: String,
    @Json(name = "lastUsedAt") val lastUsedAt: String,
    @Json(name = "expiresAt") val expiresAt: String,
    @Json(name = "current") val current: Boolean,
)

@JsonClass(generateAdapter = false)
data class DeviceDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String = "",
    @Json(name = "platform") val platform: String,
    @Json(name = "appVersion") val appVersion: String?,
    @Json(name = "createdAt") val createdAt: String,
    @Json(name = "lastSeenAt") val lastSeenAt: String,
    @Json(name = "session") val session: DeviceSessionDto?,
)

@JsonClass(generateAdapter = false)
data class DeviceListDto(
    @Json(name = "devices") val devices: List<DeviceDto>,
)

@JsonClass(generateAdapter = false)
data class UpdateMeRequestDto(
    @Json(name = "displayName") val displayName: String,
)

@JsonClass(generateAdapter = false)
data class LocationSharingRequestDto(
    @Json(name = "enabled") val enabled: Boolean,
)

@JsonClass(generateAdapter = false)
data class DeleteAccountRequestDto(
    @Json(name = "confirm") val confirm: Boolean,
)

/** The structured error envelope of every non-2xx response. */
@JsonClass(generateAdapter = false)
data class ApiErrorDto(
    @Json(name = "code") val code: String,
    @Json(name = "message") val message: String?,
    @Json(name = "requestId") val requestId: String?,
)

/** Error thrown for every non-2xx API response, parsed from the envelope. */
class ApiException(
    val code: String,
    override val message: String,
    val requestId: String?,
) : RuntimeException("[$code] $message")

/** Unwraps a Retrofit response or throws the parsed error envelope. */
fun <T> retrofit2.Response<T>.orThrow(): T {
    if (isSuccessful) {
        return body() ?: throw ApiException(
            code = "EMPTY_BODY",
            message = "The server returned an empty body.",
            requestId = null,
        )
    }
    val error = runCatching {
        errorBody()?.string()?.let { body ->
            apiMoshi.adapter(ApiErrorDto::class.java).fromJson(body)
        }
    }.getOrNull()
    throw ApiException(
        code = error?.code ?: "HTTP_${code()}",
        message = error?.message ?: "Unexpected server error.",
        requestId = error?.requestId,
    )
}
