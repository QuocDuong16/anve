package com.anve.app.ui.places

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/** One address suggestion; only a title, a readable line and a point. */
data class PlaceSuggestion(val title: String, val subtitle: String, val latitude: Double, val longitude: Double)

sealed interface PlaceSearchOutcome {
    data class Found(val suggestions: List<PlaceSuggestion>) : PlaceSearchOutcome
    data object NoResult : PlaceSearchOutcome

    /** No network or the OS lookup failed; the caller keeps map and GPS paths. */
    data object Failed : PlaceSearchOutcome

    /** The device ships no geocoder backend (some AOSP or de-Googled builds). */
    data object Unavailable : PlaceSearchOutcome
}

/** Queries shorter than this are never sent: fewer letters only add noise and needless lookups. */
const val PLACE_SEARCH_MIN_CHARS = 3

fun normalizePlaceQuery(raw: String): String? =
    raw.trim().replace(Regex("\\s+"), " ").takeIf { it.length >= PLACE_SEARCH_MIN_CHARS }

fun interface PlaceSearcher {
    suspend fun search(query: String): PlaceSearchOutcome
}

/**
 * Address lookup through the Android system Geocoder. The typed text leaves
 * the device to the OS location service, which the editor says in plain words
 * next to the field; nothing is stored and no AnVề server sees the query.
 */
class AndroidPlaceSearcher(context: Context) : PlaceSearcher {
    private val appContext = context.applicationContext

    override suspend fun search(query: String): PlaceSearchOutcome {
        val normalized = normalizePlaceQuery(query) ?: return PlaceSearchOutcome.NoResult
        if (!Geocoder.isPresent()) return PlaceSearchOutcome.Unavailable
        val geocoder = Geocoder(appContext, Locale("vi", "VN"))
        return try {
            val addresses = fetch(geocoder, normalized)
            val suggestions = addresses.mapNotNull { it.toSuggestion(normalized) }
            if (suggestions.isEmpty()) PlaceSearchOutcome.NoResult else PlaceSearchOutcome.Found(suggestions)
        } catch (_: IOException) {
            PlaceSearchOutcome.Failed
        } catch (_: IllegalArgumentException) {
            PlaceSearchOutcome.Failed
        }
    }

    private suspend fun fetch(geocoder: Geocoder, query: String): List<Address> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(
                    query,
                    MAX_RESULTS,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses)
                        }

                        override fun onError(errorMessage: String?) {
                            // Same outcome as a thrown lookup failure on older releases.
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, MAX_RESULTS).orEmpty()
            }
        }

    private fun Address.toSuggestion(query: String): PlaceSuggestion? {
        if (!hasLatitude() || !hasLongitude()) return null
        val line = (0..maxAddressLineIndex).mapNotNull { getAddressLine(it) }.joinToString(", ")
        val title = featureName?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) } ?: line.substringBefore(",").ifBlank { query }
        val subtitle = line.ifBlank { listOfNotNull(locality, adminArea, countryName).joinToString(", ") }
        return PlaceSuggestion(title, subtitle, latitude, longitude)
    }

    private companion object {
        const val MAX_RESULTS = 5
    }
}
