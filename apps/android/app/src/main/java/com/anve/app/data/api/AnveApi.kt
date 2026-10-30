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
import retrofit2.http.Query

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
    suspend fun me(
        @Header("X-Anve-Expected-Owner") expectedOwner: String? = null,
    ): Response<UserDto>

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
    suspend fun myCircle(
        @Header("X-Anve-Expected-Owner") expectedOwner: String? = null,
    ): Response<CircleMembershipDto>

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
        @Header("X-Anve-Expected-Owner") expectedOwner: String? = null,
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

    @GET("api/v1/places")
    suspend fun places(@Header("X-Anve-Expected-Owner") owner: String): Response<PlaceListDto>

    @POST("api/v1/places")
    suspend fun createPlace(@Header("X-Anve-Expected-Owner") owner: String, @Body body: PlaceInputDto): Response<PlaceDto>

    @PATCH("api/v1/places/{placeId}")
    suspend fun updatePlace(@Header("X-Anve-Expected-Owner") owner: String, @Path("placeId") id: String, @Body body: PlaceInputDto): Response<PlaceDto>

    @DELETE("api/v1/places/{placeId}")
    suspend fun deletePlace(@Header("X-Anve-Expected-Owner") owner: String, @Path("placeId") id: String): Response<Unit>

    @GET("api/v1/places/{placeId}/access")
    suspend fun placeAccess(@Header("X-Anve-Expected-Owner") owner: String, @Path("placeId") id: String): Response<PlaceAccessDto>

    @PUT("api/v1/places/{placeId}/access/{memberId}")
    suspend fun sharePlace(@Header("X-Anve-Expected-Owner") owner: String, @Path("placeId") id: String, @Path("memberId") member: String): Response<Unit>

    @DELETE("api/v1/places/{placeId}/access/{memberId}")
    suspend fun unsharePlace(@Header("X-Anve-Expected-Owner") owner: String, @Path("placeId") id: String, @Path("memberId") member: String): Response<Unit>

    @GET("api/v1/places/{placeId}/monitor")
    suspend fun placeMonitor(@Header("X-Anve-Expected-Owner") owner: String, @Path("placeId") id: String): Response<PlaceMonitorDto>

    @GET("api/v1/places/{placeId}/eligible-recipients")
    suspend fun eligiblePlaceRecipients(@Header("X-Anve-Expected-Owner") owner: String, @Path("placeId") id: String): Response<PlaceAccessDto>

    @PUT("api/v1/places/{placeId}/monitor")
    suspend fun setPlaceMonitor(@Header("X-Anve-Expected-Owner") owner: String, @Path("placeId") id: String, @Body body: PlaceMonitorRequestDto): Response<PlaceMonitorDto>

    @DELETE("api/v1/places/{placeId}/monitor")
    suspend fun stopPlaceMonitor(@Header("X-Anve-Expected-Owner") owner: String, @Path("placeId") id: String): Response<Unit>

    @GET("api/v1/places/events")
    suspend fun placeEvents(@Header("X-Anve-Expected-Owner") owner: String, @Query("limit") limit: Int = 50): Response<PlaceEventListDto>

    @GET("api/v1/places/events/{eventId}")
    suspend fun placeEvent(@Header("X-Anve-Expected-Owner") owner: String, @Path("eventId") eventId: String): Response<PlaceEventDto>

    @GET("api/v1/me/location-capture-generation")
    suspend fun captureGeneration(@Header("X-Anve-Expected-Owner") owner: String): Response<CaptureGenerationDto>

    @POST("api/v1/sos")
    suspend fun createSos(@Header("X-Anve-Expected-Owner") owner: String, @Body body: SosCreateDto): Response<SosEventDto>

    @POST("api/v1/sos/{eventId}/cancel")
    suspend fun cancelSos(@Header("X-Anve-Expected-Owner") owner: String, @Path("eventId") eventId: String): Response<Unit>

    @GET("api/v1/sos")
    suspend fun listSos(@Header("X-Anve-Expected-Owner") owner: String): Response<SosListDto>

    @GET("api/v1/sos/{eventId}")
    suspend fun getSos(@Header("X-Anve-Expected-Owner") owner: String, @Path("eventId") eventId: String): Response<SosEventDto>

    @POST("api/v1/sos/{eventId}/acknowledge")
    suspend fun acknowledgeSos(@Header("X-Anve-Expected-Owner") owner: String, @Path("eventId") eventId: String): Response<SosEventDto>

    @POST("api/v1/sos/{eventId}/resolve")
    suspend fun resolveSos(@Header("X-Anve-Expected-Owner") owner: String, @Path("eventId") eventId: String): Response<SosEventDto>

    @GET("api/v1/sos/{eventId}/recipients")
    suspend fun sosRecipients(@Header("X-Anve-Expected-Owner") owner: String, @Path("eventId") eventId: String): Response<SosRecipientsDto>

    @DELETE("api/v1/sos/{eventId}/recipients/{recipientId}")
    suspend fun revokeSosRecipient(@Header("X-Anve-Expected-Owner") owner: String, @Path("eventId") eventId: String, @Path("recipientId") recipientId: String): Response<Unit>

    @PUT("api/v1/devices/push-token")
    suspend fun registerPushToken(@Header("X-Anve-Expected-Owner") owner: String, @Body body: PushTokenDto): Response<Unit>

    @DELETE("api/v1/devices/push-token")
    suspend fun unregisterPushToken(@Header("X-Anve-Expected-Owner") owner: String): Response<Unit>

    @GET("api/v1/devices/push-token")
    suspend fun pushRegistrationStatus(@Header("X-Anve-Expected-Owner") owner: String): Response<PushRegistrationDto>

    @GET("api/v1/notifications/status")
    suspend fun pushProviderStatus(@Header("X-Anve-Expected-Owner") owner: String): Response<PushProviderStatusDto>

    @POST("api/v1/locations/batch")
    suspend fun uploadLocationBatch(
        @Header("X-Anve-Expected-Owner") owner: String,
        @Body body: LocationBatchRequestDto,
    ): Response<LocationBatchResponseDto>

    @GET("api/v1/location-sharing/audience")
    suspend fun locationAudience(
        @Header("X-Anve-Expected-Owner") expectedOwner: String? = null,
    ): Response<LocationAudienceDto>

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
        @Header("X-Anve-Expected-Owner") expectedOwner: String? = null,
    ): Response<LatestVisibleLocationsDto>
    @GET("api/v1/locations/history")
    suspend fun history(
        @Header("X-Anve-Expected-Owner") expectedOwner: String,
        @Query("ownerId") owner: String,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("limit") limit: Int = 100,
        @Query("cursor") cursor: String? = null,
    ): Response<HistoryPageDto>

    @GET("api/v1/me/location-history/retention")
    suspend fun historyRetention(@Header("X-Anve-Expected-Owner") expectedOwner: String): Response<HistoryRetentionDto>

    @PUT("api/v1/me/location-history/retention")
    suspend fun setHistoryRetention(
        @Header("X-Anve-Expected-Owner") expectedOwner: String,
        @Body body: HistoryRetentionUpdateDto,
    ): Response<HistoryRetentionDto>

    @DELETE("api/v1/me/location-history")
    suspend fun deleteHistory(
        @Header("X-Anve-Expected-Owner") expectedOwner: String,
        @Query("from") from: String?,
        @Query("to") to: String,
    ): Response<Unit>
}
