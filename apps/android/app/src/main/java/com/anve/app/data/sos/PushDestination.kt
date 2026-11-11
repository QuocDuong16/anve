package com.anve.app.data.sos

import java.util.UUID

/** A notification intent is a hint, never evidence of access to an event. */
data class PushDestination(val kind: String, val eventId: String)
data class IncomingPush(val kind: String?, val eventId: String?, val ownerId: String?)

val JOURNEY_PUSH_KINDS = setOf("JOURNEY_ACTIVE", "JOURNEY_ARRIVED", "JOURNEY_ENDED", "JOURNEY_CANCELLED", "JOURNEY_EXPIRED")

fun pushDestination(kind: String?, eventId: String?, intentOwner: String?, currentOwner: String?): PushDestination? {
    val safeKind = kind ?: return null
    if (intentOwner == null || intentOwner != currentOwner || (safeKind !in setOf("SOS", "PLACE") && safeKind !in JOURNEY_PUSH_KINDS) || eventId == null) return null
    if (runCatching { UUID.fromString(eventId) }.getOrNull()?.toString() != eventId.lowercase()) return null
    return PushDestination(safeKind, eventId)
}
