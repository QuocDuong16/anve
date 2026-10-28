package com.anve.app.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * The AnVề HTTP surface, mirroring packages/api-contracts/openapi.yaml.
 * Every method returns the raw [Response] so callers can distinguish
 * structured API errors (envelope present) from transport failures.
 */
interface AnveApi {
    @GET("api/v1/auth/config")
    suspend fun authConfig(): Response<AuthConfigDto>

    @POST("api/v1/auth/google")
    suspend fun signInWithGoogle(
        @Body body: GoogleSignInRequestDto,
    ): Response<SignInResponseDto>

    @POST("api/v1/auth/dev-login")
    suspend fun devLogin(
        @Body body: DevLoginRequestDto,
    ): Response<SignInResponseDto>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(
        @Body body: RefreshRequestDto,
    ): Response<RefreshResponseDto>

    @POST("api/v1/auth/signout")
    suspend fun signOut(
        @Header("X-Anve-Expected-Owner") expectedOwner: String? = null,
    ): Response<Unit>

    /**
     * Revokes a session whose token was already removed from local storage
     * (the offline sign-out retry): the bearer header is passed explicitly
     * because the interceptor has no session to attach.
     */
    @POST("api/v1/auth/signout")
    suspend fun signOutWithAuthorization(
        @Header("Authorization") authorization: String,
        @Header("X-Anve-Expected-Owner") expectedOwner: String? = null,
    ): Response<Unit>

    @GET("api/v1/me")
    suspend fun me(): Response<UserDto>

    @GET("api/v1/me")
    suspend fun meWithAuthorization(
        @Header("Authorization") authorization: String,
        @Header("X-Anve-Expected-Owner") expectedOwner: String,
    ): Response<UserDto>

    @PATCH("api/v1/me")
    suspend fun updateMe(
        @Body body: UpdateMeRequestDto,
    ): Response<UserDto>

    /**
     * Retrofit rejects `@Body` on a plain `@DELETE`; the contract requires
     * the confirmation object, so the request is declared through @HTTP
     * with hasBody = true (same verb and path).
     */
    @HTTP(method = "DELETE", path = "api/v1/me", hasBody = true)
    suspend fun deleteMe(
        @Body body: DeleteAccountRequestDto,
    ): Response<Unit>

    @PUT("api/v1/me/location-sharing")
    suspend fun setLocationSharing(
        @Body body: LocationSharingRequestDto,
        @Header("X-Anve-Expected-Owner") expectedOwner: String? = null,
    ): Response<UserDto>

    @PUT("api/v1/me/location-sharing")
    suspend fun setLocationSharingWithAuthorization(
        @Header("Authorization") authorization: String,
        @Header("X-Anve-Expected-Owner") expectedOwner: String,
        @Body body: LocationSharingRequestDto,
    ): Response<UserDto>

    @GET("api/v1/devices")
    suspend fun devices(): Response<DeviceListDto>

    @DELETE("api/v1/devices/{deviceId}")
    suspend fun revokeDevice(
        @Path("deviceId") deviceId: String,
    ): Response<Unit>

    @GET("api/v1/circles/mine")
    suspend fun myCircle(): Response<CircleMembershipDto>

    @POST("api/v1/circles")
    suspend fun createCircle(
        @Body body: CreateCircleRequestDto,
    ): Response<CircleMembershipDto>

    @PATCH("api/v1/circles/{circleId}")
    suspend fun renameCircle(
        @Path("circleId") circleId: String,
        @Body body: CreateCircleRequestDto,
    ): Response<CircleDto>

    @GET("api/v1/circles/{circleId}/members")
    suspend fun members(
        @Path("circleId") circleId: String,
    ): Response<MemberListDto>

    @POST("api/v1/circles/{circleId}/members/{userId}/remove")
    suspend fun removeMember(
        @Path("circleId") circleId: String,
        @Path("userId") userId: String,
    ): Response<Unit>

    @POST("api/v1/circles/{circleId}/leave")
    suspend fun leaveCircle(
        @Path("circleId") circleId: String,
        @Body body: LeaveCircleRequestDto,
    ): Response<Unit>

    @POST("api/v1/circles/{circleId}/invites")
    suspend fun createInvite(
        @Path("circleId") circleId: String,
        @Body body: CreateInviteRequestDto = CreateInviteRequestDto(),
    ): Response<InviteDto>

    @GET("api/v1/circles/{circleId}/invites")
    suspend fun invites(
        @Path("circleId") circleId: String,
    ): Response<InviteListDto>

    @DELETE("api/v1/invites/{inviteId}")
    suspend fun revokeInvite(
        @Path("inviteId") inviteId: String,
    ): Response<Unit>

    @POST("api/v1/invites/redeem")
    suspend fun redeemInvite(
        @Body body: RedeemInviteRequestDto,
    ): Response<CircleMembershipDto>

    @POST("api/v1/locations/batch")
    suspend fun uploadLocationBatch(
        @Body body: LocationBatchRequestDto,
    ): Response<LocationBatchResponseDto>

    @GET("api/v1/location-sharing/audience")
    suspend fun locationAudience(): Response<LocationAudienceDto>

    @PUT("api/v1/location-sharing/audience/{viewerId}")
    suspend fun grantLocationAudience(
        @Path("viewerId") viewerId: String,
        @Header("X-Anve-Expected-Owner") expectedOwner: String,
    ): Response<LocationAudienceGrantDto>

    @DELETE("api/v1/location-sharing/audience/{viewerId}")
    suspend fun revokeLocationAudience(
        @Path("viewerId") viewerId: String,
        @Header("X-Anve-Expected-Owner") expectedOwner: String,
    ): Response<Unit>

    @GET("api/v1/locations/visible/latest")
    suspend fun latestVisibleLocations(
        @Header("If-None-Match") etag: String? = null,
    ): Response<LatestVisibleLocationsDto>
}
