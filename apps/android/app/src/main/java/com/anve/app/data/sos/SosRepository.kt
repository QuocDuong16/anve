package com.anve.app.data.sos

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.MemberDto
import com.anve.app.data.api.SosEventDto
import com.anve.app.data.api.SosRecipientDto
import com.anve.app.data.api.orThrow

interface SosDataSource {
    suspend fun members(owner: String): List<MemberDto>
    suspend fun events(owner: String): List<SosEventDto>
    suspend fun event(owner: String, id: String): SosEventDto
    suspend fun recipients(owner: String, id: String): List<SosRecipientDto>
    suspend fun revokeRecipient(owner: String, id: String, recipient: String)
    suspend fun acknowledge(owner: String, id: String)
    suspend fun resolve(owner: String, id: String)
    suspend fun cancel(owner: String, id: String)
    suspend fun pushRegistered(owner: String): Boolean
    suspend fun pushConfigured(owner: String): Boolean
}

class SosRepository(private val api: AnveApi) : SosDataSource {
    override suspend fun members(owner: String): List<MemberDto> {
        val circle = api.myCircle(owner).orThrow().circle ?: return emptyList()
        return api.members(circle.id, owner).orThrow().members.filter { it.userId != owner }
    }
    override suspend fun events(owner: String): List<SosEventDto> = api.listSos(owner).orThrow().events
    override suspend fun event(owner: String, id: String): SosEventDto = api.getSos(owner, id).orThrow()
    override suspend fun recipients(owner: String, id: String): List<SosRecipientDto> = api.sosRecipients(owner, id).orThrow().recipients
    override suspend fun revokeRecipient(owner: String, id: String, recipient: String) { api.revokeSosRecipient(owner, id, recipient).orThrow() }
    override suspend fun acknowledge(owner: String, id: String) { api.acknowledgeSos(owner, id).orThrow() }
    override suspend fun resolve(owner: String, id: String) { api.resolveSos(owner, id).orThrow() }
    override suspend fun cancel(owner: String, id: String) { api.cancelSos(owner, id).orThrow() }
    override suspend fun pushRegistered(owner: String): Boolean = api.pushRegistrationStatus(owner).orThrow().registered
    override suspend fun pushConfigured(owner: String): Boolean = api.pushProviderStatus(owner).orThrow().configured
}
