package com.anve.app.data.api

import com.squareup.moshi.JsonClass
import java.util.UUID

@JsonClass(generateAdapter = false)
data class AvatarStateDto(val revision: Long, val operationId: String? = null, val imageId: String? = null) {
    fun checked(): AvatarStateDto {
        require(revision >= 0)
        if (revision == 0L) require(operationId == null && imageId == null)
        else {
            require(operationId != null && UUID.fromString(operationId) != UUID(0, 0))
            require(imageId == null || imageId == operationId)
        }
        return this
    }
}
