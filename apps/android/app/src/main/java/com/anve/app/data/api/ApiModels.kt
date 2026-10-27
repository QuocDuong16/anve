package com.anve.app.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.DateTimeException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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
    @Json(name = "locationSharingVersion") val locationSharingVersion: Long = 0,
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
    @Json(name = "expectedVersion") val expectedVersion: Long,
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
    val httpStatus: Int? = null,
    val retryAfterMillis: Long? = null,
) : RuntimeException("[$code] $message")

internal fun parseRetryAfterMillis(value: String?, nowMillis: Long): Long? {
    if (value.isNullOrBlank()) return null
    val seconds = value.toLongOrNull()
    if (seconds != null) return seconds.coerceAtLeast(0).coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1_000
    return try {
        val at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        (at - nowMillis).coerceAtLeast(0).coerceAtMost(MAX_RETRY_AFTER_MILLIS)
    } catch (_: DateTimeException) {
        null
    }
}

private const val MAX_RETRY_AFTER_SECONDS = 7L * 24 * 60 * 60
private const val MAX_RETRY_AFTER_MILLIS = MAX_RETRY_AFTER_SECONDS * 1_000

/** Unwraps a Retrofit response or throws the parsed error envelope. */
@Suppress("UNCHECKED_CAST")
fun <T> retrofit2.Response<T>.orThrow(): T {
    if (isSuccessful) {
        // 204/205 carry no body by definition; endpoints returning Unit use
        // these statuses, so null is the expected success shape there.
        if (code() == 204 || code() == 205) return Unit as T
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
        httpStatus = code(),
        retryAfterMillis = parseRetryAfterMillis(headers()["Retry-After"], System.currentTimeMillis()),
    )
}

// ── Locations (M3) ───────────────────────────────────────────────────────────

@JsonClass(generateAdapter = false)
data class LocationEventDto(
    @Json(name = "eventId") val eventId: String,
    @Json(name = "clientDeviceId") val clientDeviceId: String,
    @Json(name = "recordedAt") val recordedAt: String,
    @Json(name = "latitude") val latitude: Double,
    @Json(name = "longitude") val longitude: Double,
    @Json(name = "horizontalAccuracyMeters") val horizontalAccuracyMeters: Double,
    @Json(name = "altitudeMeters") val altitudeMeters: Double? = null,
    @Json(name = "speedMetersPerSecond") val speedMetersPerSecond: Double? = null,
    @Json(name = "bearingDegrees") val bearingDegrees: Double? = null,
    @Json(name = "provider") val provider: String,
    @Json(name = "isMock") val isMock: Boolean = false,
    @Json(name = "schemaVersion") val schemaVersion: Int = 1,
)

@JsonClass(generateAdapter = false)
data class LocationBatchRequestDto(
    @Json(name = "events") val events: List<LocationEventDto>,
)

@JsonClass(generateAdapter = false)
data class LocationBatchResponseDto(
    @Json(name = "accepted") val accepted: List<String> = emptyList(),
    @Json(name = "duplicates") val duplicates: List<String> = emptyList(),
    @Json(name = "receivedAt") val receivedAt: String? = null,
)

// ── Circles (M2) ─────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = false)
data class CircleDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "createdBy") val createdBy: String,
    @Json(name = "createdAt") val createdAt: String,
)

@JsonClass(generateAdapter = false)
data class MembershipDto(
    @Json(name = "circleId") val circleId: String,
    @Json(name = "userId") val userId: String,
    @Json(name = "role") val role: String,
    @Json(name = "joinedAt") val joinedAt: String,
)

@JsonClass(generateAdapter = false)
data class CircleMembershipDto(
    @Json(name = "circle") val circle: CircleDto?,
    @Json(name = "membership") val membership: MembershipDto?,
)

@JsonClass(generateAdapter = false)
data class MemberDto(
    @Json(name = "userId") val userId: String,
    @Json(name = "displayName") val displayName: String,
    @Json(name = "role") val role: String,
    @Json(name = "joinedAt") val joinedAt: String,
)

@JsonClass(generateAdapter = false)
data class MemberListDto(
    @Json(name = "members") val members: List<MemberDto>,
)

@JsonClass(generateAdapter = false)
data class InviteDto(
    @Json(name = "id") val id: String,
    @Json(name = "circleId") val circleId: String,
    @Json(name = "code") val code: String,
    @Json(name = "createdBy") val createdBy: String,
    @Json(name = "expiresAt") val expiresAt: String,
    @Json(name = "createdAt") val createdAt: String,
)

@JsonClass(generateAdapter = false)
data class InviteListDto(
    @Json(name = "invites") val invites: List<InviteDto>,
)

@JsonClass(generateAdapter = false)
data class CreateCircleRequestDto(
    @Json(name = "name") val name: String,
)

@JsonClass(generateAdapter = false)
data class CreateInviteRequestDto(
    @Json(name = "expiresInHours") val expiresInHours: Int? = null,
)

@JsonClass(generateAdapter = false)
data class RedeemInviteRequestDto(
    @Json(name = "code") val code: String,
)

@JsonClass(generateAdapter = false)
data class LeaveCircleRequestDto(
    @Json(name = "dissolve") val dissolve: Boolean,
)
