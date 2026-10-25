package com.anve.app.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
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
    suspend fun signOut(): Response<Unit>

    @GET("api/v1/me")
    suspend fun me(): Response<UserDto>

    @PATCH("api/v1/me")
    suspend fun updateMe(
        @Body body: UpdateMeRequestDto,
    ): Response<UserDto>

    @DELETE("api/v1/me")
    suspend fun deleteMe(
        @Body body: DeleteAccountRequestDto,
    ): Response<Unit>

    @PUT("api/v1/me/location-sharing")
    suspend fun setLocationSharing(
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
}
