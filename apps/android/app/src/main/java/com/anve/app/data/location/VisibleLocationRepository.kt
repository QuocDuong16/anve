package com.anve.app.data.location

import com.anve.app.data.api.AnveApi
import com.anve.app.data.api.ApiException
import com.anve.app.data.api.MemberDto
import com.anve.app.data.api.VisibleLocationDto
import com.anve.app.data.api.orThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

data class VisibleLocationSnapshot(
    val ownerId: String,
    val locations: List<VisibleLocationDto> = emptyList(),
    val loading: Boolean = true,
    val connected: Boolean = false,
    val lastSuccessfulPollMillis: Long? = null,
    val dataObservedElapsedMillis: Long? = null,
    val error: String? = null,
)

data class AudienceMember(
    val userId: String,
    val displayName: String,
    val role: String,
    val canViewLocation: Boolean,
)

data class SharingManagementSnapshot(
    val hasCircle: Boolean = false,
    val circleName: String? = null,
    val consentEnabled: Boolean = false,
    val members: List<AudienceMember> = emptyList(),
)

/**
 * Foreground-only, conditional polling for the M4 latest snapshot. Every
 * changed snapshot comes from the server's current authorization query; no
 * location is persisted on device or treated as an authorization cache.
 */
class VisibleLocationRepository(
    private val api: AnveApi,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
) {

    fun observeLatest(ownerId: String): Flow<VisibleLocationSnapshot> = flow {
        var etag: String? = null
        var visible = emptyList<VisibleLocationDto>()
        var failures = 0
        var dataObservedElapsed: Long? = null
        var lastSuccessfulPoll: Long? = null
        emit(VisibleLocationSnapshot(ownerId = ownerId))
        while (true) {
            try {
                val response = api.latestVisibleLocations(etag)
                when {
                    response.code() == 304 -> {
                        failures = 0
                        lastSuccessfulPoll = nowMillis()
                        emit(
                            VisibleLocationSnapshot(
                                ownerId = ownerId,
                                locations = visible,
                                loading = false,
                                connected = true,
                                lastSuccessfulPollMillis = lastSuccessfulPoll,
                                dataObservedElapsedMillis = dataObservedElapsed,
                            ),
                        )
                    }
                    response.isSuccessful -> {
                        val body = response.body()
                            ?: throw IOException("Location snapshot response was empty")
                        visible = body.locations.filter { it.isSafeForMap() }
                        etag = response.headers()["ETag"]
                        failures = 0
                        dataObservedElapsed = elapsedMillis()
                        lastSuccessfulPoll = nowMillis()
                        emit(
                            VisibleLocationSnapshot(
                                ownerId = ownerId,
                                locations = visible,
                                loading = false,
                                connected = true,
                                lastSuccessfulPollMillis = lastSuccessfulPoll,
                                dataObservedElapsedMillis = dataObservedElapsed,
                            ),
                        )
                    }
                    else -> response.orThrow()
                }
                delay(pollIntervalMillis)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failures++
                val authExpired = (error as? ApiException)?.httpStatus in setOf(401, 403)
                if (authExpired) {
                    visible = emptyList()
                    etag = null
                    dataObservedElapsed = null
                    lastSuccessfulPoll = null
                }
                emit(
                    VisibleLocationSnapshot(
                        ownerId = ownerId,
                        locations = visible,
                        loading = false,
                        connected = false,
                        lastSuccessfulPollMillis = lastSuccessfulPoll,
                        dataObservedElapsedMillis = dataObservedElapsed,
                        error = if (authExpired) {
                            "Phiên đăng nhập đã hết hạn. Đăng nhập lại để xem vị trí."
                        } else {
                            "Đang chờ kết nối để cập nhật vị trí."
                        },
                    ),
                )
                delay(backoffMillis(failures))
            }
        }
    }

    suspend fun sharingManagement(): SharingManagementSnapshot {
        val account = api.me().orThrow()
        val current = api.myCircle().orThrow()
        val circle = current.circle ?: return SharingManagementSnapshot(
            consentEnabled = account.locationSharingEnabled,
        )
        val members = api.members(circle.id).orThrow().members
        val granted = api.locationAudience().orThrow().grants.mapTo(mutableSetOf()) { it.viewerId }
        return SharingManagementSnapshot(
            hasCircle = true,
            circleName = circle.name,
            consentEnabled = account.locationSharingEnabled,
            members = members.map { it.toAudienceMember() }.map { member ->
                member.copy(canViewLocation = account.locationSharingEnabled && member.userId in granted)
            },
        )
    }

    suspend fun setViewerGrant(ownerId: String, viewerId: String, enabled: Boolean) {
        if (enabled) {
            api.grantLocationAudience(viewerId, ownerId).orThrow()
        } else {
            api.revokeLocationAudience(viewerId, ownerId).orThrow()
        }
    }

    private fun backoffMillis(failures: Int): Long =
        (pollIntervalMillis * (1L shl failures.coerceIn(0, 4))).coerceAtMost(MAX_BACKOFF_MILLIS)

    private fun VisibleLocationDto.isSafeForMap(): Boolean {
        if (ownerId.isBlank() || displayName.isBlank()) return false
        if (confidence !in CONFIDENCE_STATES) return false
        if (ageMillis != null && ageMillis < 0) return false
        val hasCoordinates = latitude != null && longitude != null
        if (!hasCoordinates) {
            return latitude == null && longitude == null && recordedAt == null && receivedAt == null &&
                ageMillis == null && confidence == "UNKNOWN"
        }
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
        if (horizontalAccuracyMeters?.isFinite() == false || horizontalAccuracyMeters?.let { it < 0.0 } == true) {
            return false
        }
        return recordedAt != null && receivedAt != null && ageMillis != null
    }

    private fun MemberDto.toAudienceMember() = AudienceMember(
        userId = userId,
        displayName = displayName,
        role = role,
        canViewLocation = false,
    )

    companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS = 10_000L
        const val MAX_BACKOFF_MILLIS = 60_000L
        val CONFIDENCE_STATES = setOf("FRESH", "DELAYED", "STALE", "OFFLINE", "UNKNOWN")
    }
}
