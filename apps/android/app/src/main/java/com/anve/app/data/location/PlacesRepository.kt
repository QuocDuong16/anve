package com.anve.app.data.location

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.PlaceDto
import com.anve.app.data.api.PlaceEventDto
import com.anve.app.data.api.PlaceInputDto
import com.anve.app.data.api.PlaceMonitorDto
import com.anve.app.data.api.PlaceMonitorRequestDto
import com.anve.app.data.api.orThrow

interface PlacesDataSource {
    suspend fun places(owner: String): List<PlaceDto>
    suspend fun events(owner: String): List<PlaceEventDto>
    suspend fun members(owner: String): SharingManagementSnapshot
    suspend fun access(owner: String, place: String): List<String>
    suspend fun monitor(owner: String, place: String): PlaceMonitorDto?
    suspend fun eligibleRecipients(owner: String, place: String): List<String>
    suspend fun create(owner: String, input: PlaceInputDto): PlaceDto
    suspend fun update(owner: String, place: String, input: PlaceInputDto): PlaceDto
    suspend fun delete(owner: String, place: String)
    suspend fun share(owner: String, place: String, member: String, enabled: Boolean)
    suspend fun configure(owner: String, place: String, recipients: List<String>): PlaceMonitorDto
    suspend fun stop(owner: String, place: String)
}

class PlacesRepository(private val api: AnveApi, private val sharing: SharingManagementDataSource) : PlacesDataSource {
    override suspend fun places(owner: String) = api.places(owner).orThrow().places
    override suspend fun events(owner: String) = api.placeEvents(owner).orThrow().events
    override suspend fun members(owner: String) = sharing.sharingManagement(owner)
    override suspend fun access(owner: String, place: String) = api.placeAccess(owner, place).orThrow().memberIds
    override suspend fun eligibleRecipients(owner: String, place: String) = api.eligiblePlaceRecipients(owner, place).orThrow().memberIds
    override suspend fun monitor(owner: String, place: String): PlaceMonitorDto? {
        val response = api.placeMonitor(owner, place)
        return if (response.code() == 404) null else response.orThrow()
    }
    override suspend fun create(owner: String, input: PlaceInputDto) = api.createPlace(owner, input).orThrow()
    override suspend fun update(owner: String, place: String, input: PlaceInputDto) = api.updatePlace(owner, place, input).orThrow()
    override suspend fun delete(owner: String, place: String) { api.deletePlace(owner, place).orThrow() }
    override suspend fun share(owner: String, place: String, member: String, enabled: Boolean) {
        (if (enabled) api.sharePlace(owner, place, member) else api.unsharePlace(owner, place, member)).orThrow()
    }
    override suspend fun configure(owner: String, place: String, recipients: List<String>) =
        api.setPlaceMonitor(owner, place, PlaceMonitorRequestDto(recipients)).orThrow()
    override suspend fun stop(owner: String, place: String) { api.stopPlaceMonitor(owner, place).orThrow() }
}
