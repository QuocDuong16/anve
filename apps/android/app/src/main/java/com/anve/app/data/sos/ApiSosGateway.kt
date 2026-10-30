package com.anve.app.data.sos

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.SosCreateDto
import com.anve.app.data.api.orThrow

class SosCancelledException : Exception()

/** Owner-bound API calls; provider acceptance and acknowledgement are separate reads. */
class ApiSosGateway(private val api: AnveApi) : SosSyncGateway {
    override suspend fun create(owner: String, item: SosOutboxItem) {
        try {
            api.createSos(owner, SosCreateDto(item.eventId, item.recordedAt, item.recipientIds)).orThrow()
        } catch (e: ApiException) {
            if (e.code == "SOS_CANCELLED") throw SosCancelledException()
            throw e
        }
    }

    override suspend fun cancel(owner: String, eventId: String) {
        api.cancelSos(owner, eventId).orThrow()
    }
}
