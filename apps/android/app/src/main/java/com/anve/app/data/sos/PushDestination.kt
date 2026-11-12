package com.anve.app.data.sos

import java.util.UUID

/** A notification intent is a hint, never evidence of access to an event. */
data class PushDestination(val kind: String, val eventId: String)
data class IncomingPush(val kind: String?, val eventId: String?, val ownerId: String?)

val JOURNEY_PUSH_KINDS = setOf("JOURNEY_ACTIVE", "JOURNEY_ARRIVED", "JOURNEY_ENDED", "JOURNEY_CANCELLED", "JOURNEY_EXPIRED", "JOURNEY_STALE")

fun validPushSignal(kind: String, eventId: String, episode: Int): Boolean =
    (kind in setOf("SOS", "PLACE", "CIRCLE_INVITE") || kind in JOURNEY_PUSH_KINDS) &&
        runCatching { UUID.fromString(eventId) }.getOrNull()?.toString() == eventId.lowercase() &&
        if (kind == "JOURNEY_STALE") episode > 0 else episode == 0

/** Lifecycle dedup keys stay stable across upgrades; each stale episode can alert once. */
fun pushSignalIdentity(kind: String, eventId: String, episode: Int): String =
    if (kind == "JOURNEY_STALE") "$kind:$eventId:$episode" else "$kind:$eventId"

fun pushDestination(kind: String?, eventId: String?, intentOwner: String?, currentOwner: String?): PushDestination? {
    val safeKind = kind ?: return null
    if (intentOwner == null || intentOwner != currentOwner || (safeKind !in setOf("SOS", "PLACE", "CIRCLE_INVITE") && safeKind !in JOURNEY_PUSH_KINDS) || eventId == null) return null
    if (runCatching { UUID.fromString(eventId) }.getOrNull()?.toString() != eventId.lowercase()) return null
    return PushDestination(safeKind, eventId)
}
