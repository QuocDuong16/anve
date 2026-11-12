package com.anve.app.data.api

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class JourneyDestinationDto(val label: String, val latitude: Double, val longitude: Double)

@JsonClass(generateAdapter = false)
data class CreateJourneyDto(
    val journeyId: String,
    val preparedAt: String,
    val durationMinutes: Int,
    val destination: JourneyDestinationDto,
    val recipientIds: List<String>,
    val automaticArrival: Boolean = false,
    val staleAfterMinutes: Int = 0,
)

@JsonClass(generateAdapter = false)
data class JourneyRecipientDto(val userId: String, val displayName: String)

@JsonClass(generateAdapter = false)
data class JourneyProgressDto(val recordedAt: String, val distanceMeters: Double, val accuracyMeters: Double, val isMock: Boolean)

@JsonClass(generateAdapter = false)
data class JourneyDto(
    val id: String,
    val ownerId: String,
    val ownerName: String,
    val state: String,
    val startedAt: String,
    val expiresAt: String,
    val destination: JourneyDestinationDto,
    val endedAt: String? = null,
    val recipients: List<JourneyRecipientDto> = emptyList(),
    val progress: JourneyProgressDto? = null,
    val automaticArrival: Boolean = false,
    val staleAfterMinutes: Int = 0,
    val arrivalSource: String? = null,
    val lastLiveUpdateAt: String? = null,
    val staleSince: String? = null,
    val staleEpisode: Int = 0,
)

@JsonClass(generateAdapter = false)
data class JourneysDto(val journeys: List<JourneyDto>)
