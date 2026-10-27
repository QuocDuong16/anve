package com.anve.app.data.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response as OkHttpResponse
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelsTest {

    private val moshi = apiMoshi

    @Test
    fun `parses the error envelope`() {
        val json = """
            {"code":"UNAUTHENTICATED","message":"Authentication required.","requestId":"req-1"}
        """.trimIndent()
        val error = moshi.adapter(ApiErrorDto::class.java).fromJson(json)!!
        assertEquals("UNAUTHENTICATED", error.code)
        assertEquals("Authentication required.", error.message)
        assertEquals("req-1", error.requestId)
    }

    @Test
    fun `parses the sign-in response`() {
        val json = """
            {
              "user": {
                "id": "u-1",
                "email": "me@example.com",
                "displayName": "Mai",
                "locationSharingEnabled": false,
                "createdAt": "2026-10-25T10:00:00Z"
              },
              "session": {
                "accessToken": "at",
                "refreshToken": "rt",
                "accessExpiresAt": "2026-10-25T10:30:00Z",
                "refreshExpiresAt": "2026-11-24T10:00:00Z"
              }
            }
        """.trimIndent()
        val response = moshi.adapter(SignInResponseDto::class.java).fromJson(json)!!
        assertEquals("me@example.com", response.user.email)
        assertEquals(false, response.user.locationSharingEnabled)
        assertNull(response.user.avatarUrl)
        assertEquals("at", response.session.accessToken)
    }

    @Test
    fun `parses the device list with nullable session`() {
        val json = """
            {"devices": [{
              "id": "d-1", "name": "Pixel 8", "platform": "android",
              "createdAt": "2026-10-25T10:00:00Z", "lastSeenAt": "2026-10-25T12:00:00Z"
            }, {
              "id": "d-2", "name": "Galaxy", "platform": "android",
              "createdAt": "2026-10-25T10:00:00Z", "lastSeenAt": "2026-10-25T12:00:00Z",
              "session": {"id": "s-2", "createdAt": "2026-10-25T10:00:00Z",
                          "lastUsedAt": "2026-10-25T12:00:00Z",
                          "expiresAt": "2026-11-24T10:00:00Z", "current": true}
            }]}
        """.trimIndent()
        val devices = moshi.adapter(DeviceListDto::class.java).fromJson(json)!!
        assertEquals(2, devices.devices.size)
        assertNull(devices.devices[0].session)
        assertTrue(devices.devices[1].session!!.current)
    }

    @Test
    fun `orThrow parses the envelope of a failing response`() {
        val body = """
            {"code":"RATE_LIMITED","message":"Too many requests.","requestId":"req-9"}
        """.trimIndent()
        val response = retrofit2.Response.error<Unit>(429, body.toResponseBody("application/json".toMediaType()))
        val thrown = runCatching { response.orThrow() }.exceptionOrNull()
        assertTrue(thrown is ApiException)
        thrown as ApiException
        assertEquals("RATE_LIMITED", thrown.code)
        assertEquals("Too many requests.", thrown.message)
        assertEquals("req-9", thrown.requestId)
    }

    @Test
    fun `Retry-After supports seconds and HTTP dates within a bounded window`() {
        assertEquals(120_000L, parseRetryAfterMillis("120", nowMillis = 1_000))
        assertEquals(0L, parseRetryAfterMillis("-5", nowMillis = 1_000))
        assertNull(parseRetryAfterMillis("not-a-date", nowMillis = 1_000))
        assertEquals(
            2_000L,
            parseRetryAfterMillis("Thu, 01 Jan 1970 00:00:03 GMT", nowMillis = 1_000),
        )
    }

    @Test
    fun `orThrow carries retry-after metadata from HTTP response`() {
        val request = Request.Builder().url("https://anve.invalid/api/v1/locations/batch").build()
        val raw = OkHttpResponse.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .header("Retry-After", "17")
            .build()
        val response = retrofit2.Response.error<Unit>(
            """{"code":"RATE_LIMITED","message":"Slow down","requestId":"req-9"}"""
                .toResponseBody("application/json".toMediaType()),
            raw,
        )

        val thrown = runCatching { response.orThrow() }.exceptionOrNull() as ApiException

        assertEquals(429, thrown.httpStatus)
        assertEquals(17_000L, thrown.retryAfterMillis)
    }
}
