package com.anve.app.data.auth

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.RefreshRequestDto
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Attaches the access token to every request and, on a 401, rotates the
 * session once and retries (ADR 0002: the server hashes both tokens, so a
 * rotated pair invalidates the old one immediately).
 *
 * Refresh is single-flight under a lock and rotation-aware: if the token the
 * server rejected was already replaced by a concurrent refresh, the newer
 * token is returned without another round-trip. A dead refresh token clears
 * the local session — the user signs in again; a transport failure keeps it
 * so the next request can retry.
 */
class SessionInterceptor(
    private val storage: SessionStorage,
    private val plainApi: AnveApi,
) : Interceptor {

    private val refreshLock = ReentrantLock()

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { storage.current()?.accessToken }
        val request = if (token == null) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        val response = chain.proceed(request)
        if (response.code != 401 || token == null) {
            return response
        }
        val fresh = refreshSync(token)
        if (fresh == null) {
            return response
        }
        response.close()
        val retried = chain.request().newBuilder()
            .header("Authorization", "Bearer $fresh")
            .build()
        return chain.proceed(retried)
    }

    /** See the class doc; internal for direct testing. */
    internal fun refreshSync(staleAccessToken: String): String? = refreshLock.withLock {
        val current = runBlocking { storage.current() } ?: return null
        if (current.accessToken != staleAccessToken) {
            // Another request already rotated the pair.
            return current.accessToken
        }
        try {
            runBlocking {
                val response = plainApi.refresh(RefreshRequestDto(current.refreshToken))
                val tokens = response.body()?.session
                if (response.isSuccessful && tokens != null) {
                    storage.save(
                        StoredSession(
                            accessToken = tokens.accessToken,
                            refreshToken = tokens.refreshToken,
                            accessExpiresAt = tokens.accessExpiresAt,
                            refreshExpiresAt = tokens.refreshExpiresAt,
                        ),
                    )
                    tokens.accessToken
                } else {
                    if (response.code() == 401) storage.clear()
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
