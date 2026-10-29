package com.anve.app.data.location

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.HistoryPageDto
import com.anve.app.data.api.HistoryRetentionDto
import com.anve.app.data.api.HistoryRetentionUpdateDto
import com.anve.app.data.api.orThrow

data class HistoryMember(val ownerId: String, val displayName: String)

interface HistoryDataSource {
    suspend fun members(owner: String): List<HistoryMember>
    suspend fun page(owner: String, member: String, from: String, to: String, cursor: String?): HistoryPageDto
    suspend fun retention(owner: String): HistoryRetentionDto
    suspend fun setRetention(owner: String, days: Int): HistoryRetentionDto
    suspend fun delete(owner: String, from: String?, to: String)
}

class HistoryRepository(private val api: AnveApi) : HistoryDataSource {
    override suspend fun members(owner: String): List<HistoryMember> =
        api.latestVisibleLocations(expectedOwner = owner).orThrow().locations.map { HistoryMember(it.ownerId, it.displayName) }

    override suspend fun page(owner: String, member: String, from: String, to: String, cursor: String?): HistoryPageDto =
        api.history(owner, member, from, to, cursor = cursor).orThrow()

    override suspend fun retention(owner: String): HistoryRetentionDto = api.historyRetention(owner).orThrow()

    override suspend fun setRetention(owner: String, days: Int): HistoryRetentionDto =
        api.setHistoryRetention(owner, HistoryRetentionUpdateDto(days)).orThrow()

    override suspend fun delete(owner: String, from: String?, to: String) {
        api.deleteHistory(owner, from, to).orThrow()
    }
}
