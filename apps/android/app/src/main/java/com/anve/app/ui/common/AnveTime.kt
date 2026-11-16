package com.anve.app.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The single formatter for instants shown to the user, so one fact never
 * ships under several formats across screens.
 *
 * - [full] renders an absolute date-time ("dd/MM/yyyy HH:mm"); everything
 *   that labels a moment outside the current view — SOS outbox rows, place
 *   events, invite expiry, journey start/end, map snapshots — uses it.
 * - [seconds] renders "HH:mm:ss" for the history day list, where the selected
 *   date is already on screen and second-level ordering matters.
 *
 * Parse failures return the caller's fallback instead of guessing; the raw
 * ISO string is a legitimate fallback for values that were never parsed
 * successfully anywhere else either.
 */
object AnveTime {
    private val full = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private val seconds = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    /** ISO-8601 timestamp → "dd/MM/yyyy HH:mm", or [fallback] when unreadable. */
    fun full(iso: String?, fallback: String): String =
        if (iso == null) fallback else runCatching { full.format(Instant.parse(iso)) }.getOrDefault(fallback)

    /** Epoch millis → "dd/MM/yyyy HH:mm", or [fallback] when null. */
    fun full(millis: Long?, fallback: String): String =
        if (millis == null) fallback else full.format(Instant.ofEpochMilli(millis))

    /** ISO-8601 timestamp → "HH:mm", or [fallback] when unreadable. */
    fun clock(iso: String?, fallback: String): String =
        if (iso == null) fallback else runCatching { clock.format(Instant.parse(iso)) }.getOrDefault(fallback)

    /** ISO-8601 timestamp → "HH:mm:ss", or [fallback] when unreadable. */
    fun seconds(iso: String?, fallback: String): String =
        if (iso == null) fallback else runCatching { seconds.format(Instant.parse(iso)) }.getOrDefault(fallback)

    /** Elapsed age in milliseconds → human Vietnamese phrase ("vừa xong", "15 phút trước", "2 giờ trước"). */
    fun relativeAge(ageMillis: Long): String {
        val safe = ageMillis.coerceAtLeast(0L)
        val minutes = safe / 60_000L
        return when {
            minutes < 1L -> "vừa xong"
            minutes < 60L -> "$minutes phút trước"
            minutes < 1_440L -> "${minutes / 60L} giờ trước"
            else -> "${minutes / 1_440L} ngày trước"
        }
    }

    /** Elapsed age in milliseconds → compact pin badge ("Vừa xong", "15p trước", "2g trước"). */
    fun compactAge(ageMillis: Long): String {
        val safe = ageMillis.coerceAtLeast(0L)
        val minutes = safe / 60_000L
        return when {
            minutes < 1L -> "Vừa xong"
            minutes < 60L -> "${minutes}p trước"
            minutes < 1_440L -> "${minutes / 60L}g trước"
            else -> "${minutes / 1_440L}ng trước"
        }
    }
}
