package com.anve.app.data.avatar

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.AvatarStateDto
import com.anve.app.data.api.orThrow
import com.anve.app.data.auth.SessionStorage
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

interface AvatarGateway {
    suspend fun state(owner: String): AvatarStateDto
    suspend fun replace(owner: String, operation: String, revision: Long, image: ByteArray): AvatarStateDto
    suspend fun remove(owner: String, operation: String, revision: Long): AvatarStateDto
    suspend fun image(owner: String, subject: String, imageId: String): ByteArray
}

/** Owner headers and before/after checks also cover an interceptor refreshing another login. */
class AvatarRepository(private val api: AnveApi, private val sessions: SessionStorage) : AvatarGateway {
    private suspend fun requireOwner(owner: String) {
        if (sessions.current()?.userId != owner) throw CancellationException("Avatar account changed")
    }

    override suspend fun state(owner: String): AvatarStateDto {
        requireOwner(owner)
        val result = api.avatarState(owner).orThrow().checked()
        requireOwner(owner)
        return result
    }

    override suspend fun replace(owner: String, operation: String, revision: Long, image: ByteArray): AvatarStateDto {
        requireOwner(owner)
        val result = api.replaceAvatar(owner, operation, revision, image.toRequestBody("image/jpeg".toMediaType())).orThrow().checked()
        requireOwner(owner)
        return result
    }

    override suspend fun remove(owner: String, operation: String, revision: Long): AvatarStateDto {
        requireOwner(owner)
        val result = api.removeAvatar(owner, operation, revision).orThrow().checked()
        requireOwner(owner)
        return result
    }

    override suspend fun image(owner: String, subject: String, imageId: String): ByteArray {
        requireOwner(owner)
        val result = api.avatarImage(owner, subject, imageId).orThrow().use { body ->
            require(body.contentType()?.type == "image" && body.contentType()?.subtype == "jpeg")
            body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_AVATAR_BYTES) throw IOException("Avatar response too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        requireOwner(owner)
        return result
    }

    companion object { const val MAX_AVATAR_BYTES = 128 * 1024 }
}
