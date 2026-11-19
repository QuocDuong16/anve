package com.anve.app.tracking

import com.anve.app.data.api.UserDto
import com.anve.app.data.auth.AuthRepository
import com.anve.app.data.circles.CircleRepository

/**
 * The two server reads/writes the coordinator needs. A small interface
 * keeps the state machine testable without HTTP and documents exactly
 * which server state tracking depends on.
 */
interface TrackingServerGateway {
    suspend fun currentUser(): UserDto

    suspend fun setLocationSharing(
        enabled: Boolean,
        expectedVersion: Long,
        ownerUserId: String,
        operationId: String,
    ): UserDto

    suspend fun setLowBatteryAlerts(
        enabled: Boolean,
        expectedVersion: Long,
        ownerUserId: String,
    ): UserDto = throw UnsupportedOperationException("low-battery alerts gateway not implemented")

    suspend fun hasCircle(): Boolean
}

class AppTrackingServerGateway(
    private val auth: AuthRepository,
    private val circles: CircleRepository,
) : TrackingServerGateway {

    override suspend fun currentUser(): UserDto = auth.me()

    override suspend fun setLocationSharing(
        enabled: Boolean,
        expectedVersion: Long,
        ownerUserId: String,
        operationId: String,
    ): UserDto = auth.setLocationSharing(enabled, expectedVersion, ownerUserId, operationId)

    override suspend fun setLowBatteryAlerts(
        enabled: Boolean,
        expectedVersion: Long,
        ownerUserId: String,
    ): UserDto = auth.setLowBatteryAlerts(enabled, expectedVersion, ownerUserId)

    override suspend fun hasCircle(): Boolean = circles.myCircle().circle != null
}
