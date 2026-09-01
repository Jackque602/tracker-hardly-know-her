package dev.jackque.roamed.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import dev.jackque.roamed.core.geo.CellKey
import dev.jackque.roamed.core.geo.TileMath
import dev.jackque.roamed.data.db.VisitedPlaceEntity
import dev.jackque.roamed.data.repo.Fix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Names the countries and regions you pass through, so the stats screen can say "17 countries"
 * rather than only "0.004% of the planet".
 *
 * Deliberately lazy: it only asks the geocoder when a fix lands in a ~150 km square that has not
 * been asked about before, so a day of commuting costs at most one lookup. Everything about it is
 * best effort - no network, no geocoder, no answer, no problem.
 */
class PlaceResolver(private val context: Context) {

    private val askedRegions = HashSet<Long>()

    /** Returns a place to record, or null if there is nothing new (or nothing knowable). */
    suspend fun resolve(fix: Fix): VisitedPlaceEntity? {
        if (!Geocoder.isPresent()) return null
        val region = CellKey.pack(
            TileMath.cellX(fix.longitude, REGION_ZOOM),
            TileMath.cellY(fix.latitude, REGION_ZOOM),
        )
        synchronized(askedRegions) {
            if (!askedRegions.add(region)) return null
        }

        val address = withTimeoutOrNull(TIMEOUT_MILLIS) { lookup(fix) }
        val countryCode: String? = address?.countryCode
        if (countryCode.isNullOrBlank()) {
            // Let a failed lookup be retried later rather than poisoning the region forever.
            synchronized(askedRegions) { askedRegions.remove(region) }
            return null
        }
        return VisitedPlaceEntity(
            countryCode = countryCode,
            countryName = address?.countryName ?: countryCode,
            adminArea = address?.adminArea ?: "",
            firstSeen = fix.timestamp,
            lastSeen = fix.timestamp,
        )
    }

    private suspend fun lookup(fix: Fix): Address? {
        val geocoder = Geocoder(context, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                try {
                    geocoder.getFromLocation(fix.latitude, fix.longitude, 1) { addresses ->
                        if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(fix.latitude, fix.longitude, 1)?.firstOrNull()
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private companion object {
        /** z6 squares are roughly 150 km across at mid latitudes - about one lookup per region. */
        const val REGION_ZOOM = 6
        const val TIMEOUT_MILLIS = 10_000L
    }
}
