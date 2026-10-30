package com.anve.app.data.auth

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.RefreshRequestDto
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
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
        val original = chain.request()
        val expectedOwner = original.header(EXPECTED_OWNER_HEADER)
        val current = runBlocking { storage.current() }
        if (expectedOwner != null && current?.userId != expectedOwner) {
            throw IOException("The active account changed before the request started")
        }
        val token = current?.accessToken
        val request = original.newBuilder()
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        val response = chain.proceed(request)
        if (response.code != 401 || token == null) {
            return response
        }
        val fresh = refreshSync(token, current.userId)
        if (fresh == null) {
            return response
        }
        val afterRefresh = runBlocking { storage.current() }
        if (afterRefresh == null || afterRefresh.accessToken != fresh || afterRefresh.userId != current.userId ||
            (expectedOwner != null && afterRefresh.userId != expectedOwner)
        ) {
            return response
        }
        response.close()
        val retried = original.newBuilder()
            .header("Authorization", "Bearer $fresh")
            .build()
        return chain.proceed(retried)
    }

    /** See the class doc; internal for direct testing. */
    internal fun refreshSync(staleAccessToken: String): String? = refreshSync(staleAccessToken, null)

    internal fun refreshSync(staleAccessToken: String, expectedOwner: String?): String? = refreshLock.withLock {
        val current = runBlocking { storage.current() } ?: return null
        if (expectedOwner != null && current.userId != expectedOwner) return null
        if (current.accessToken != staleAccessToken) {
            // Another request rotated the same owner's pair. Unknown-owner
            // sessions fail closed because they cannot be safely attributed.
            return current.accessToken.takeIf { expectedOwner != null && current.userId == expectedOwner }
        }
        try {
            runBlocking {
                val response = plainApi.refresh(RefreshRequestDto(current.refreshToken))
                val tokens = response.body()?.session
                if (response.isSuccessful && tokens != null) {
                    val rotated = current.copy(
                            accessToken = tokens.accessToken,
                            refreshToken = tokens.refreshToken,
                            accessExpiresAt = tokens.accessExpiresAt,
                            refreshExpiresAt = tokens.refreshExpiresAt,
                        )
                    if (storage.rotateIfMatches(current.userId, current.accessToken, rotated)) {
                        tokens.accessToken
                    } else {
                        null
                    }
                } else {
                    if (response.code() == 401) {
                        storage.clearIfMatches(current.userId, current.accessToken)
                    }
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val EXPECTED_OWNER_HEADER = "X-Anve-Expected-Owner"
    }
}
